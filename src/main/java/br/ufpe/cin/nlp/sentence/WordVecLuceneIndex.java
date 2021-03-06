package br.ufpe.cin.nlp.sentence;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.compressors.gzip.GzipUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class WordVecLuceneIndex implements VectorVocab {
	
    private static final Logger log = LoggerFactory.getLogger(WordVecLuceneIndex.class);
    private boolean indexExists = false;
    private transient IndexReader reader;
    private transient IndexSearcher searcher;
    private transient  Directory dir;
    private   transient Analyzer analyzer;
    private String indexPath;
    private int words = -1;
    private int embedSize = -1;

	public WordVecLuceneIndex(File fromFile, String indexPath, boolean reuse) {
		this.indexPath = indexPath;
		File filePath = new File(indexPath);
        if(filePath.exists()) {
        	if (!reuse) {
	            String id = UUID.randomUUID().toString();
	            log.warn("Changing index path to" + id);
	            this.indexPath = id;
        	} else {
        		indexExists = true;
        		
        		if (!filePath.isDirectory()) {
        			throw new IllegalArgumentException("indexPath exists but is not a directory");
        		}
        		if (!filePath.canRead()) {
        			throw new IllegalArgumentException("indexPath exists but cannot read from it");
        		}
        		
        		try {
					this.dir = FSDirectory.open(new File(this.indexPath).toPath());
				} catch (IOException e) {
					throw new IllegalStateException("Cannot open indexpath: " + this.indexPath, e);
				}
        	}
        }
        try {
			ensureDirExists(indexPath);
			if (!indexExists) {
				IndexWriter writer = createIndex();
				addEmbeddings(writer, fromFile);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Error creating index at " + indexPath, e);
		}
        try {
        	this.reader = DirectoryReader.open(this.dir);
        	this.searcher = new IndexSearcher(reader);
        	if (this.words == -1 || this.embedSize == -1) {
        		updateNumDocs();
        		updateEmbedSize();
        	}
        	assert(this.words != -1 && this.embedSize != -1);
        } catch (IOException e) {
        	throw new IllegalStateException("Error opening index at " + this.dir.toString(), e);
        }
	}
	
	private void updateEmbedSize() {
		try {
			Document doc = this.reader.document(1);
			float[] embed = extractEmbed(doc);
			this.embedSize = embed.length;
		} catch (IOException e) {
			throw new IllegalStateException("IOException when trying to get document from index", e);
		}
		
	}

	private void updateNumDocs() {
		this.words = this.reader.numDocs();
	}

	public boolean contains(String word) {
		Term t = new Term("word", word);
		Query query = new TermQuery(t);
		TopDocs tops;
		try {
			tops = searcher.search(query, 1);
		} catch (IOException e) {
			throw new IllegalStateException("IOException when trying to search index", e);
		}
		final boolean ret = tops.totalHits > 0;
		
		return ret;
	}
	
	public float[] embeddingFor(String word) {
		float[] ret = null;
		Term t = new Term("word", word);
		Query query = new TermQuery(t);
		TopDocs tops;
		try {
			tops = searcher.search(query, 1);
		} catch (IOException e) {
			throw new IllegalStateException("IOException when trying to search index", e);
		}
		if (tops.totalHits > 0) {
			final ScoreDoc[] scoreDoc = tops.scoreDocs;
			//log.debug("found {} hit(s) in index matching {}", scoreDoc.length, word);
			assert scoreDoc.length > 0;
			final int docIndex = scoreDoc[0].doc;
			Document doc;
			try {
				doc = this.reader.document(docIndex);
			} catch (IOException e) {
				throw new IllegalStateException("IOException when trying to get document by id", e);
			}
			ret = extractEmbed(doc);
		}
		return ret;
	}

	private float[] extractEmbed(Document doc) {
		float[] ret;
		final BytesRef embedBytes = doc.getBinaryValue("embed");
		final ByteBuffer buf = ByteBuffer.wrap(embedBytes.bytes);
		final FloatBuffer fbuf = buf.asFloatBuffer();
		fbuf.rewind();
		if (fbuf.hasArray()) {
			ret = fbuf.array();
		} else {
			ret = new float[fbuf.remaining()];
			fbuf.get(ret);
		}
		return ret;
	}

	private void addEmbeddings(IndexWriter writer, File fromFile) throws IOException {
		final BufferedReader reader = getTextFileModelReader(fromFile);

		String line = reader.readLine();
		String[] initial = line.split(" ");
		this.words = Integer.parseInt(initial[0]);
		this.embedSize = Integer.parseInt(initial[1]);
		int n = 0;
		while ((line = reader.readLine()) != null) {
			final String[] split = line.split(" ");
			assert split.length == embedSize + 1;
			final String word = split[0].toLowerCase(Locale.ENGLISH);
			float[] vector = new float[split.length - 1];
			for (int i = 1; i < split.length; i++) {
				vector[i - 1] = Float.parseFloat(split[i]);
			}
			Document doc = new Document();
			doc.add(new StringField("word", word, Store.NO));
			ByteBuffer embedBuffer = ByteBuffer.allocate(4*vector.length);
			embedBuffer.rewind();
			for (int i = 0; i < vector.length; i++) {
				embedBuffer.putFloat(vector[i]);
			}
			byte[] embedValues;
			if (embedBuffer.hasArray()) {
				embedValues = embedBuffer.array();
			} else {
				embedValues = new byte[embedBuffer.capacity()];
				embedBuffer.get(embedValues);
			}
			doc.add(new StoredField("embed", embedValues));
			writer.addDocument(doc);
			if ((++n) % 10000 == 0) writer.commit();
		}		
		writer.commit();
		writer.close();
		assert n == this.words;
		
	}

	private IndexWriter createIndex() throws IOException {
		if (this.analyzer == null) {
			this.analyzer = new StandardAnalyzer(new InputStreamReader(new ByteArrayInputStream("".getBytes())));
		}
		IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        iwc.setWriteLockTimeout(1000);
        
		
		IndexWriter writer = new IndexWriter(this.dir, iwc);
		return writer;
	}
	
	private BufferedReader getTextFileModelReader(File modelFile) throws IOException, FileNotFoundException {
		final BufferedReader reader = new BufferedReader(new InputStreamReader(
				GzipUtils.isCompressedFilename(modelFile.getName())
						? new GZIPInputStream(new FileInputStream(modelFile)) : new FileInputStream(modelFile),
				"UTF-8"));
		return reader;
	}
	

	private void ensureDirExists(String indexPath) throws IOException {
        if(dir == null) {
            log.info("Creating directory " + indexPath);
            File dir2 = new File(indexPath);
            dir2.mkdir();
            dir = FSDirectory.open(new File(indexPath).toPath());
        }
	}

	@Override
	public int numWords() {
		return this.words;
	}

	@Override
	public int embedSize() {
		return this.embedSize;
	}
}
