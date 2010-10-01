package org.hupo.psi.mi.psicquic.clustering;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hupo.psi.mi.psicquic.*;
import org.hupo.psi.mi.psicquic.clustering.job.ClusteringJob;
import org.hupo.psi.mi.psicquic.clustering.job.JobNotCompletedException;
import org.hupo.psi.mi.psicquic.clustering.job.PollResult;
import org.hupo.psi.mi.psicquic.clustering.job.dao.ClusteringServiceDaoFactory;
import org.hupo.psi.mi.psicquic.clustering.job.dao.JobDao;
import psidev.psi.mi.search.SearchResult;
import psidev.psi.mi.search.engine.SearchEngine;
import psidev.psi.mi.search.engine.impl.BinaryInteractionSearchEngine;
import psidev.psi.mi.tab.converter.tab2xml.Tab2Xml;
import psidev.psi.mi.tab.model.BinaryInteraction;
import psidev.psi.mi.tab.model.builder.MitabDocumentDefinition;
import psidev.psi.mi.xml.converter.impl254.EntrySetConverter;
import psidev.psi.mi.xml.dao.inMemory.InMemoryDAOFactory;
import psidev.psi.mi.xml254.jaxb.Attribute;
import psidev.psi.mi.xml254.jaxb.AttributeList;
import psidev.psi.mi.xml254.jaxb.Entry;
import psidev.psi.mi.xml254.jaxb.EntrySet;

import java.io.IOException;
import java.util.List;

/**
 * Default interaction clustering service.
 *
 * @author Samuel Kerrien (skerrien@ebi.ac.uk)
 * @version $Id$
 * @since 0.1
 */
public class DefaultInteractionClusteringService implements InteractionClusteringService {

    private static final Log log = LogFactory.getLog( DefaultInteractionClusteringService.class );

    private static final String NEW_LINE = System.getProperty( "line.separator" );

    /////////////////////////////////
    // InteractionClusteringService

    public String submitJob( String miql, List<Service> services ) {

        // Build a job
        final ClusteringJob job = new ClusteringJob( miql, services );

        // Generate an id based on the definition of the job
        final String jobId = job.getJobId();

        // detect if this job is already running
        ClusteringServiceDaoFactory csd = ClusteringContext.getInstance().getDaoFactory();
        final JobDao jobDao = csd.getJobDao();
        ClusteringJob existingJob;
        if ( ( existingJob = jobDao.getJob( jobId ) ) != null ) {
            // job was already submitted
            log.info( "This job was already submitted: " + existingJob.toString() );
        } else {
            // new job
            jobDao.addJob( jobId, job );
        }

        return jobId;
    }

    public PollResult poll( String jobId ) {

        ClusteringServiceDaoFactory csd = ClusteringContext.getInstance().getDaoFactory();
        final JobDao jobDao = csd.getJobDao();
        final ClusteringJob job = jobDao.getJob( jobId );
        final PollResult pr = new PollResult( job.getStatus() );

        // TODO if status is RUNNING, generate an ETA and store into 'PollResult.message'
        // TODO if status is FAILED, collect reason and store into 'PollResult.message'

        return pr;
    }

    public QueryResponse query( String jobId,
                                String query,
                                final int from,
                                final int maxResult,
                                final String resultType ) throws JobNotCompletedException,
                                                                 NotSupportedTypeException,
                                                                 PsicquicServiceException {

        ClusteringServiceDaoFactory csd = ClusteringContext.getInstance().getDaoFactory();
        final JobDao jobDao = csd.getJobDao();
        final ClusteringJob job = jobDao.getJob( jobId );

        // get Lucene index location from job
        final String indexLocation = job.getLuceneIndexLocation();
        log.debug( "Reading Lucene index from directory: " + indexLocation );
        // query chunk from Lucene
        SearchEngine searchEngine;

        try {
            searchEngine = new BinaryInteractionSearchEngine( indexLocation );
        } catch ( IOException e ) {
            log.error( "Could not initialize Lucene index before querying: " + indexLocation, e );
            throw new PsicquicServiceException( "Problem creating SearchEngine using directory: " + indexLocation, e );
        }

        final int blockSize = Math.min( maxResult, 200 );

        log.info( "Lucene search: [query='" + query + "'; from='" + from + "'; blockSize='" + blockSize + "']" );
        SearchResult searchResult = searchEngine.search( query, from, blockSize );

        // preparing the response (PSICQUIC style response)
        QueryResponse queryResponse = new QueryResponse();
        ResultInfo resultInfo = new ResultInfo();
        resultInfo.setBlockSize( blockSize );
        resultInfo.setFirstResult( from );
        resultInfo.setResultType( resultType );
        resultInfo.setTotalResults( searchResult.getTotalCount() );
        queryResponse.setResultInfo( resultInfo );

        // Build a RequestInfo to enable reuse of method borrowed from psicquic-ws
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setBlockSize( blockSize );
        requestInfo.setFirstResult( from );
        requestInfo.setResultType( resultType );

        ResultSet resultSet = createResultSet( query, searchResult, requestInfo );
        queryResponse.setResultSet( resultSet );

        // return the data
        return queryResponse;
    }

    ///////////////////////////////
    // PSICQUIC WS

    public List<String> getSupportedReturnTypes() {
        return SUPPORTED_RETURN_TYPES;
    }

    protected ResultSet createResultSet( String query, SearchResult searchResult, RequestInfo requestInfo ) throws PsicquicServiceException,
                                                                                                                   NotSupportedTypeException {
        ResultSet resultSet = new ResultSet();

        String resultType = ( requestInfo.getResultType() != null ) ? requestInfo.getResultType() : RETURN_TYPE_DEFAULT;

        if ( RETURN_TYPE_MITAB25.equals( resultType ) ) {
            if ( log.isDebugEnabled() ) log.debug( "Creating PSI-MI TAB" );

            String mitab = createMitabResults( searchResult );
            resultSet.setMitab( mitab );
        } else if ( RETURN_TYPE_XML25.equals( resultType ) ) {
            if ( log.isDebugEnabled() ) log.debug( "Creating PSI-MI XML" );

            EntrySet jEntrySet = createEntrySet( searchResult );
            resultSet.setEntrySet( jEntrySet );

            // add some annotations
            if ( !jEntrySet.getEntry().isEmpty() ) {
                AttributeList attrList = new AttributeList();

                Entry entry = jEntrySet.getEntry().iterator().next();

                Attribute attr = new Attribute();
                attr.setValue( "Data retrieved using the PSICQUIC service. Query: " + query );
                attrList.getAttribute().add( attr );

                Attribute attr2 = new Attribute();
                attr2.setValue( "Total results found: " + searchResult.getTotalCount() );
                attrList.getAttribute().add( attr2 );

                // add warning if the batch size requested is higher than the maximum allowed
                if ( requestInfo.getBlockSize() > BLOCKSIZE_MAX && BLOCKSIZE_MAX < searchResult.getTotalCount() ) {
                    Attribute attrWarning = new Attribute();
                    attrWarning.setValue( "Warning: The requested block size (" + requestInfo.getBlockSize() + ") was higher than the maximum allowed (" + BLOCKSIZE_MAX + ") by PSICQUIC the service. " +
                                          BLOCKSIZE_MAX + " results were returned from a total found of " + searchResult.getTotalCount() );
                    attrList.getAttribute().add( attrWarning );
                }

                entry.setAttributeList( attrList );
            }

        } else if ( RETURN_TYPE_COUNT.equals( resultType ) ) {
            if ( log.isDebugEnabled() ) log.debug( "Count query" );
            // nothing to be done here
        } else {
            throw new NotSupportedTypeException( "Not supported return type: " + resultType + " - Supported types are: " + getSupportedReturnTypes() );
        }

        return resultSet;
    }

    protected String createMitabResults( SearchResult searchResult ) {
        MitabDocumentDefinition docDef = new MitabDocumentDefinition();

        List<BinaryInteraction> binaryInteractions = searchResult.getData();

        StringBuilder sb = new StringBuilder( binaryInteractions.size() * 512 );

        for ( BinaryInteraction binaryInteraction : binaryInteractions ) {
            String binaryInteractionString = docDef.interactionToString( binaryInteraction );
            sb.append( binaryInteractionString );
            sb.append( NEW_LINE );
        }
        return sb.toString();
    }

    private EntrySet createEntrySet( SearchResult searchResult ) throws PsicquicServiceException {
        if ( searchResult.getData().isEmpty() ) {
            return new EntrySet();
        }

        Tab2Xml tab2Xml = new Tab2Xml();
        try {
            psidev.psi.mi.xml.model.EntrySet mEntrySet = tab2Xml.convert( searchResult.getData() );

            EntrySetConverter converter = new EntrySetConverter();
            converter.setDAOFactory( new InMemoryDAOFactory() );

            return converter.toJaxb( mEntrySet );

        } catch ( Exception e ) {
            throw new PsicquicServiceException( "Problem converting results to PSI-MI XML", e );
        }
    }
}
