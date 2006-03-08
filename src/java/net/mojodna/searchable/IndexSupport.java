/*
Copyright 2005-2006 Seth Fitzsimmons <seth@note.amherst.edu>

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package net.mojodna.searchable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexModifier;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Searcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Support class for classes that access an index.  Contains common methods
 * for indexing and searching as well as constants.
 * 
 * @author Seth Fitzsimmons
 */
public abstract class IndexSupport {
    private static final Logger log = Logger.getLogger( IndexSupport.class );
    
    /** Default index path ($TEMP/lucene) */
    public static final String DEFAULT_INDEX_PATH = System.getProperty("java.io.tmpdir") + File.separatorChar + "lucene";
    /** Default Analyzer */
    public static final Analyzer DEFAULT_ANALYZER = new StandardAnalyzer();
    /** Default boost value (1.0) */
    public static final float DEFAULT_BOOST_VALUE = 1F;
    /** Name of id field */
    public static final String ID_FIELD_NAME = "_id";
    /** Name of id type field (contains the class name for the id) */
    public static final String ID_TYPE_FIELD_NAME = "_id-type";
    /** Name of type field (contains the class name for the indexed item) */
    public static final String TYPE_FIELD_NAME = "_type";
    /** Compound id/type field */
    public static final String COMPOUND_ID_FIELD_NAME = "_cid";
    /** Prefix for keyword fields intended for sorting */
    public static final String SORTABLE_PREFIX = "_sort-";
    /** Batch merge factor */
    public static final int BATCH_MERGE_FACTOR = 500;
    
    /** Collection of field names internal to searchable */
    protected static final Collection PRIVATE_FIELD_NAMES = Arrays.asList( new String[] { ID_FIELD_NAME, ID_TYPE_FIELD_NAME, TYPE_FIELD_NAME, COMPOUND_ID_FIELD_NAME } );
    
    /** Analyzer in use */
    private Analyzer analyzer = DEFAULT_ANALYZER;
    /** Index path */
    private String indexPath = DEFAULT_INDEX_PATH;
    /** Is this in batch mode? */
    private boolean batchMode;
    
    /** Shared index directories */
    private static Map<String,Directory> indexDirectories = new ConcurrentHashMap<String, Directory>();
    /** Shared IndexReaders */
    private static Map<String,IndexReader> readers = new ConcurrentHashMap<String, IndexReader>();
    /** Shared IndexModifiers */
    private static Map<String,IndexModifier> modifiers = new ConcurrentHashMap<String, IndexModifier>();
    /** Shared IndexSearchers */
    private static Map<String,IndexSearcher> searchers = new ConcurrentHashMap<String, IndexSearcher>();
    
    /**
     * Gets the index path in use.
     * 
     * @return Index path.
     */
    public String getIndexPath() {
    	return indexPath;
    }
    
    public void setIndexPath(final String indexPath) {
    	this.indexPath = indexPath;
    }
    
    /**
     * Gets the underlying Directory containing this index.
     * 
     * @return Directory holding this index.
     */
    protected Directory getIndexDirectory() throws IndexException {
    	if ( !indexDirectories.containsKey( getIndexPath() ) ) {
            final File indexFile = new File( getIndexPath() );

            if ( !indexFile.exists() ) {
            	// create the index directory if necessary
                indexFile.mkdirs();
            }
            
            try {
            	indexDirectories.put( getIndexPath(), FSDirectory.getDirectory( indexFile, false ) );
            }
            catch (final IOException e) {
            	throw new IndexException( e );
            }
    	}
    		
        return indexDirectories.get( getIndexPath() );
    }
    
    /**
     * Gets the Analyzer in use.
     * 
     * @return Analyzer in use.
     */
    public Analyzer getAnalyzer() {
        return analyzer;
    }
    
    /**
     * Sets the Analyzer in use.
     * 
     * @param analyzer Analyzer to use.
     */
    public void setAnalyzer(final Analyzer analyzer) {
        this.analyzer = analyzer;
    }
    
    /**
     * Is this running in batch mode?
     * 
     * @return Whether this indexer is running in batch mode.
     */
    public boolean isBatchMode() {
    	return batchMode;
    }
    
    /**
     * Sets whether this indexer should run in batch mode.
     * 
     * @param batchMode Whether this indexer should run in batch mode.
     */
    public void setBatchMode(final boolean batchMode) {
    	this.batchMode = batchMode;
    }
    
    /**
     * Creates a new index.
     * 
     * @throws IndexException
     */
    public IndexModifier createIndex() throws IndexException {
    	log.debug("Creating index.");
    	try {
        	if ( modifiers.containsKey( getIndexPath() ) )
        		modifiers.get( getIndexPath() ).close();
        	
    		modifiers.put( getIndexPath(), new IndexModifier( getIndexDirectory(), getAnalyzer(), true ) );
    	}
    	catch (final IOException e) {
    		log.error("Could not create index: " + e.getMessage(), e);
    		throw new IndexException( "Unable to create index.", e );
    	}
    	
    	return modifiers.get( getIndexPath() );
    }
    
    /**
     * Closes (and optimizes) the active index.
     * 
     * @throws IndexException
     */
    public void close() throws IndexException {
    	if ( this instanceof BatchIndexer ) {
    		log.debug("Flushing...");
    		((BatchIndexer) this).flush();
    	}
    	
        optimizeIndex();
        
        try {
        	if ( readers.containsKey( getIndexPath() ) ) {
        		readers.get( getIndexPath() ).close();
        		readers.remove( getIndexPath() );
        	}
        	if ( modifiers.containsKey( getIndexPath() ) ) {
        		modifiers.get( getIndexPath() ).close();
        		modifiers.remove( getIndexPath() );
        	}
        }
        catch (final IOException e) {
        	throw new IndexException("Could not close index.", e);
        }
    }
    
    /**
     * Optimize the active index.
     * 
     * @throws IndexException
     */
    protected void optimizeIndex() throws IndexException {
    	try {
    		getIndexModifier().optimize();
    	}
    	catch (final IOException e) {
    		log.error("Could not optimize index: " + e.getMessage(), e);
    		throw new IndexException( "Unable to optimize index.", e );
    	}
    }
    
    /**
     * Gets the IndexReader associated with this index, refreshing the reader
     * if it has become out of date.
     * 
     * @return IndexReader associated with this index.
     * @throws IndexException
     */
    protected IndexReader getIndexReader() throws IndexException {
    	try {
    		if ( readers.containsKey( getIndexPath() ) ) {
    			// refresh if the reader is out of date
    			// if the reader is operating on a RAMDirectory, versions will have to be compared
    			if ( !readers.get( getIndexPath() ).isCurrent() && !isBatchMode() ) {
    				log.debug("Refreshing reader...");
    				readers.put( getIndexPath(), IndexReader.open( getIndexDirectory() ) );
    			}
    		} else {
    			// attempt to open an IndexReader
    			// TODO future optimization: wrap in a RAMDirectory
    			log.debug("Creating an IndexReader...");
    			readers.put( getIndexPath(), IndexReader.open( getIndexDirectory() ) );
    		}
     	}
 		catch (final IOException e) {
 			log.debug("Could not create IndexReader: " + e.getMessage() );
 			throw new IndexException( e );
 		}
    	
    	
    	return readers.get( getIndexPath() );
    }
    
    /**
     * Gets the IndexModifier associated with this index, creating the index if necessary.
     * 
     * @return IndexModifier associated with this index.
     * @throws IndexException
     */
    protected IndexModifier getIndexModifier() throws IndexException {
    	if ( modifiers.containsKey( getIndexPath() ) ) {
    		return modifiers.get( getIndexPath() );
    	} else {
    		try {
    			try {
    				log.debug("Creating an IndexModifier...");
    				modifiers.put( getIndexPath(), new IndexModifier( getIndexDirectory(), getAnalyzer(), false ) );
    			}
    			catch (final FileNotFoundException e) {
    				// a failure opening a non-existent index causes it to be locked anyway
    				IndexReader.unlock( getIndexDirectory() );
    				modifiers.put( getIndexPath(), createIndex() );
    			}
    			
				if ( isBatchMode() )
					modifiers.get( getIndexPath() ).setMergeFactor( BATCH_MERGE_FACTOR );
				
				return modifiers.get( getIndexPath() );
    		}
    		catch (final IOException e) {
    			log.error("Could not create IndexModifier: " + e.getMessage(), e);
    			throw new IndexException( "Could not create IndexModifier.", e );
    		}
    	}
    }
    
    /**
     * Gets the IndexSearcher associated with this index.
     * 
     * @return IndexSearcher associated with this index.
     * @throws IndexException
     */
    protected Searcher getIndexSearcher() throws IndexException {
    	try {
    		if ( !searchers.containsKey( getIndexPath() ) || !searchers.get( getIndexPath() ).getIndexReader().isCurrent() ) {
    			searchers.put( getIndexPath(), new IndexSearcher( getIndexReader() ) );
    		}
    	}
    	catch (final IOException e) {
    		throw new IndexException("Could not create IndexSearcher.", e);
    	}
    	
    	return searchers.get( getIndexPath() );
    }
    
    @Override
    protected void finalize() {
    	try {
    		close();
    	}
    	catch (final IndexException e) {
    		log.warn("Exception while finalizing.", e);
    	}
    }
}
