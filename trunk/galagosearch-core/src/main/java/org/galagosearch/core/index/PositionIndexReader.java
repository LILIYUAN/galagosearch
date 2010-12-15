// BSD License (http://www.galagosearch.org/license)
package org.galagosearch.core.index;

import java.io.DataInput;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.galagosearch.core.parse.Document;
import org.galagosearch.core.retrieval.query.Node;
import org.galagosearch.core.retrieval.query.NodeType;
import org.galagosearch.core.retrieval.structured.DocumentOrderedIterator;
import org.galagosearch.core.retrieval.structured.ExtentIndexIterator;
import org.galagosearch.core.retrieval.structured.IndexIterator;
import org.galagosearch.core.util.ExtentArray;
import org.galagosearch.tupleflow.BufferedFileDataStream;
import org.galagosearch.tupleflow.DataStream;
import org.galagosearch.tupleflow.Processor;
import org.galagosearch.tupleflow.Utility;
import org.galagosearch.tupleflow.VByteInput;

/**
 * Reads a simple positions-based index, where each inverted list in the
 * index contains both term count information and term position information.
 * The term counts data is stored separately from term position information for
 * faster query processing when no positions are needed.
 * 
 * For now, the iterator loads everything into memory before starting query 
 * processing, which is not a workable solution for larger collections.
 * 
 * @author trevor
 */
public class PositionIndexReader implements StructuredIndexPartReader {

    public class Iterator extends ExtentIndexIterator {

        int documentCount;
        int totalPositionCount;
        VByteInput documents;
        VByteInput counts;
        VByteInput positions;
        int documentIndex;
        int currentDocument;
        int currentCount;
        ExtentArray extentArray;
        IndexReader.Iterator iterator;
        // to support skipping
        VByteInput skips;
        VByteInput skipPositions;
        DataStream skipPositionsStream;
        DataStream documentsStream;
        DataStream countsStream;
        DataStream positionsStream;
        int skipDistance;
        int skipResetDistance;
        long numSkips;
        long skipsRead;
        long nextSkipDocument;
        long lastSkipPosition;
        long documentsByteFloor;
        long countsByteFloor;
        long positionsByteFloor;

        Iterator(IndexReader.Iterator iterator) throws IOException {
            this.iterator = iterator;
            load();
        }

        // Even though we check for skips multiple times, in terms of how the data is loaded
        // its easier to do the parts when appropriate
        private void load() throws IOException {
            long startPosition = iterator.getValueStart();
            long endPosition = iterator.getValueEnd();

            RandomAccessFile input = reader.getInput();
            input.seek(startPosition);
            DataInput stream = new VByteInput(reader.getInput());

            // metadata
            int options = stream.readInt();
            documentCount = stream.readInt();
            totalPositionCount = stream.readInt();
            if ((options & DocumentOrderedIterator.HAS_SKIPS) == DocumentOrderedIterator.HAS_SKIPS) {
                skipDistance = stream.readInt();
                skipResetDistance = stream.readInt();
                numSkips = stream.readLong();
            }

            // segment lengths
            long documentByteLength = stream.readLong();
            long countsByteLength = stream.readLong();
            long positionsByteLength = stream.readLong();
            long skipsByteLength = 0;
            long skipPositionsByteLength = 0;

            if ((options & DocumentOrderedIterator.HAS_SKIPS) == DocumentOrderedIterator.HAS_SKIPS) {
                skipsByteLength = stream.readLong();
                skipPositionsByteLength = stream.readLong();
            }

            long documentStart = input.getFilePointer();
            long documentEnd = documentStart + documentByteLength;

            long countsStart = documentEnd;
            long countsEnd = countsStart + countsByteLength;

            long positionsStart = countsEnd;
            long positionsEnd = positionsStart + positionsByteLength;


            if ((options & DocumentOrderedIterator.HAS_SKIPS) == DocumentOrderedIterator.HAS_SKIPS) {

                long skipsStart = positionsEnd;
                long skipsEnd = skipsStart + skipsByteLength;

                long skipPositionsStart = skipsEnd;
                long skipPositionsEnd = skipPositionsStart + skipPositionsByteLength;

                assert skipPositionsEnd == endPosition;

                // we do these here b/c of scoping issues w/ the variables above
                documentsStream = new BufferedFileDataStream(input, documentStart, documentEnd);
                documents = new VByteInput(documentsStream);
                countsStream = new BufferedFileDataStream(input, countsStart, countsEnd);
                counts = new VByteInput(countsStream);
                positionsStream = new BufferedFileDataStream(input, positionsStart, positionsEnd);
                positions = new VByteInput(positionsStream);
                skips = new VByteInput(new BufferedFileDataStream(input, skipsStart, skipsEnd));
                skipPositionsStream = new BufferedFileDataStream(input, skipPositionsStart,
                        skipPositionsEnd);
                skipPositions = new VByteInput(skipPositionsStream);

                // load up
                nextSkipDocument = skips.readInt();
                documentsByteFloor = 0;
                countsByteFloor = 0;
                positionsByteFloor = 0;
            } else {
                assert positionsEnd == endPosition;
                skips = null;
                skipPositions = null;
                documents = new VByteInput(new BufferedFileDataStream(input, documentStart, documentEnd));
                counts = new VByteInput(new BufferedFileDataStream(input, countsStart, countsEnd));
                positions = new VByteInput(new BufferedFileDataStream(input, positionsStart, positionsEnd));

            }

            extentArray = new ExtentArray();
            documentIndex = 0;

            loadExtents();
        }

        private void loadExtents() throws IOException {
            currentDocument += documents.readInt();
            currentCount = counts.readInt();
            extentArray.reset();

            int position = 0;
            for (int i = 0; i < currentCount; i++) {
                position += positions.readInt();
                extentArray.add(currentDocument, position, position + 1);
            }
        }

        public String getRecordString() {
            StringBuilder builder = new StringBuilder();

            builder.append(getKey());
            builder.append(",");
            builder.append(currentDocument);
            for (int i = 0; i < extentArray.getPosition(); ++i) {
                builder.append(",");
                builder.append(extentArray.getBuffer()[i].begin);
            }

            return builder.toString();
        }

        public void reset() throws IOException {
            currentDocument = 0;
            currentCount = 0;
            extentArray.reset();

            load();
        }

        public long getByteLength() throws IOException {
            return iterator.getValueLength();
        }

        public String getKey() {
            return Utility.toString(iterator.getKey());
        }
        public byte[] getKeyBytes() {
            return iterator.getKey();
        }

        public void nextEntry() throws IOException {
            documentIndex += 1;

            if (!isDone()) {
                loadExtents();
            }
        }

        public boolean nextRecord() throws IOException {
            nextEntry();
            if (!isDone()) {
                return true;
            }
            if (iterator.nextKey()) {
                reset();
                return true;
            }
            return false;
        }

        // If we have skips - it's go time
        @Override
        public boolean skipToDocument(int document) throws IOException {
            if (skips == null || document <= nextSkipDocument) {
                 return super.skipToDocument(document);
            }

            // if we're here, we're skipping
            int lastDocumentSkipped = (int) nextSkipDocument;
            while (skipsRead < numSkips
                    && document > nextSkipDocument) {
                lastDocumentSkipped = (int) nextSkipDocument;
                skipOnce();
            }
            repositionMainStreams();
            if (lastDocumentSkipped == document) {
                loadExtents();
                return true;
            } else {
                return super.skipToDocument(document); // linear from here
            }
        }

        // This only moves forward in tier 1, reads from tier 2 only when
        // needed to update floors
        //
        private void skipOnce() throws IOException {
            assert skipsRead < numSkips;
            long currentSkipPosition = lastSkipPosition + skips.readInt();

            if (skipsRead % skipResetDistance == 0) {
                // Position the skip positions stream
                skipPositionsStream.seek(currentSkipPosition);

                // now set the floor values
                documentsByteFloor = skipPositions.readInt();
                countsByteFloor = skipPositions.readInt();
                positionsByteFloor = skipPositions.readLong();
            }
            currentDocument = (int) nextSkipDocument;

            // May be at the end of the buffer
            if (skipsRead + 1 == numSkips) {
                nextSkipDocument = Integer.MAX_VALUE;
            } else {
                nextSkipDocument += skips.readInt();
            }
            skipsRead++;
            documentIndex += skipDistance;
            lastSkipPosition = currentSkipPosition;
         }

        private void repositionMainStreams() throws IOException {
            // If we just reset the floors, don't read the 2nd tier again
            if ((skipsRead - 1) % skipResetDistance == 0) {
                documentsStream.seek(documentsByteFloor);
                countsStream.seek(countsByteFloor);
                positionsStream.seek(positionsByteFloor);
            } else {
                skipPositionsStream.seek(lastSkipPosition);
                documentsStream.seek(documentsByteFloor + skipPositions.readInt());
                countsStream.seek(countsByteFloor + skipPositions.readInt());
                positionsStream.seek(positionsByteFloor + skipPositions.readLong());
            }
        }

        public boolean isDone() {
            return documentIndex >= documentCount;
        }

        public ExtentArray extents() {
            return extentArray;
        }

        public int document() {
            return currentDocument;
        }

        public int count() {
            return currentCount;
        }

        // TODO: Make this hack more refined
        public int totalDocuments() {
            return documentCount;
        }

        // TODO: Declare in an interface
        public int totalPositions() {
            return totalPositionCount;
        }
    }
    IndexReader reader;

    public PositionIndexReader(
            IndexReader reader) throws IOException {
        this.reader = reader;
    }

    public PositionIndexReader(
            String pathname) throws FileNotFoundException, IOException {
        reader = new IndexReader(pathname);
    }

    /**
     * Returns an iterator pointing at the first term in the index.
     */
    public Iterator getIterator() throws IOException {
        return new Iterator(reader.getIterator());
    }

    /**
     * Returns an iterator pointing at the specified term, or 
     * null if the term doesn't exist in the inverted file.
     */
    public Iterator getTermExtents(String term) throws IOException {
        IndexReader.Iterator iterator = reader.getIterator(Utility.fromString(term));

        if (iterator != null) {
            return new Iterator(iterator);
        }
        return null;
    }

    List<Processor<Document>> transformations() {
        return DocumentTransformationFactory.instance(reader.getManifest());
    }

    List<Processor<Document>> transformations(String field) {
        return transformations();
    }

    public void close() throws IOException {
        reader.close();
    }

    public Map<String, NodeType> getNodeTypes() {
        HashMap<String, NodeType> types = new HashMap<String, NodeType>();
        types.put("counts", new NodeType(Iterator.class));
        types.put("extents", new NodeType(Iterator.class));

        return types;
    }

    public IndexIterator getIterator(Node node) throws IOException {
        // TODO(strohman): handle stemming!!
        return getTermExtents(node.getDefaultParameter("term"));
    }

    // I add these in order to return document frequency and collection frequency
    // information for terms. Any other way from the iterators are SLOW
    // unless the headers have already been loaded. 
    // We need a better interface for these.
    // TODO:: Clean abstraction for this
    public int documentCount(String term) throws IOException {
        IndexReader.Iterator iterator = reader.getIterator(Utility.fromString(term));
        if (iterator == null) {
            return 0;
        }

        long startPosition = iterator.getValueStart();
        long endPosition = iterator.getValueEnd();

        RandomAccessFile input = reader.getInput();
        input.seek(startPosition);
        DataInput stream = new VByteInput(reader.getInput());

        // header information - have to read b/c it's compressed
        stream.readInt(); // skip option information
        int documentCount = stream.readInt();
        return documentCount;
    }

    // TODO: Clean abstraction for this
    public int termCount(String term) throws IOException {
        IndexReader.Iterator iterator = reader.getIterator(Utility.fromString(term));
        if (iterator == null) {
            return 0;
        }
        long startPosition = iterator.getValueStart();
        long endPosition = iterator.getValueEnd();

        RandomAccessFile input = reader.getInput();
        input.seek(startPosition);
        DataInput stream = new VByteInput(reader.getInput());

        // Can't just seek b/c the numbers are compressed
        stream.readInt();
        stream.readInt();
        int totalPositionCount = stream.readInt();
        return totalPositionCount;
    }
}
