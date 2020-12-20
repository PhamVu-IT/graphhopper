/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.storage.index;

import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.cursors.IntCursor;
import com.carrotsearch.hppc.predicates.IntPredicate;
import com.graphhopper.coll.GHBitSet;
import com.graphhopper.coll.GHIntHashSet;
import com.graphhopper.coll.GHTBitSet;
import com.graphhopper.geohash.SpatialKeyAlgo;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.*;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.GHPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * This class implements a Quadtree to get the closest node or edge from GPS coordinates.
 * The following properties are different to an ordinary implementation:
 * <ol>
 * <li>To reduce overall size it can use 16 instead of just 4 cell if required</li>
 * <li>Still all leafs are at the same depth, otherwise it is too complicated to calculate the Bresenham line for different
 * resolutions, especially if a leaf node could be split into a tree-node and resolution changes.</li>
 * <li>To further reduce size this Quadtree avoids storing the bounding box of every cell and calculates this per request instead.</li>
 * <li>To simplify this querying and avoid a slow down for the most frequent queries ala "lat,lon" it encodes the point
 * into a reverse spatial key {@see SpatialKeyAlgo} and can the use the resulting raw bits as cell index to recurse
 * into the subtrees. E.g. if there are 3 layers with 16, 4 and 4 cells each, then the reverse spatial key has
 * three parts: 4 bits for the cellIndex into the 16 cells, 2 bits for the next layer and 2 bits for the last layer.
 * It is the reverse spatial key and not the forward spatial key as we need the start of the index for the current
 * layer at index 0</li>
 * <li>An array structure (DataAccess) is internally used and stores the offset to the next cell.
 * E.g. in case of 4 cells, the offset is 0,1,2 or 3. Except when the leaf-depth is reached, then the value
 * is the number of node IDs stored in the cell or, if negative, just a single node ID.</li>
 * </ol>
 *
 * @author Peter Karich
 */
public class LocationIndexTree implements LocationIndex {
    // do not start with 0 as a positive value means leaf and a negative means "entry with subentries"
    static final int START_POINTER = 1;
    protected final Graph graph;
    final DataAccess dataAccess;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final int MAGIC_INT;
    private final NodeAccess nodeAccess;
    protected DistanceCalc distCalc = DistancePlaneProjection.DIST_PLANE;
    SpatialKeyAlgo keyAlgo;
    private int maxRegionSearch = 4;
    private DistanceCalc preciseDistCalc = DistanceCalcEarth.DIST_EARTH;
    private int[] entries;
    private byte[] shifts;
    // convert spatial key to index for subentry of current depth
    private long[] bitmasks;
    private int minResolutionInMeter = 300;
    private double deltaLat;
    private double deltaLon;
    private int initSizeLeafEntries = 4;
    private boolean initialized = false;
    private static final Comparator<Snap> SNAP_COMPARATOR = Comparator.comparingDouble(Snap::getQueryDistance);
    /**
     * If normed distance is smaller than this value the node or edge is 'identical' and the
     * algorithm can stop search.
     */
    private double equalNormedDelta;

    /**
     * @param g the graph for which this index should do the lookup based on latitude,longitude.
     */
    public LocationIndexTree(Graph g, Directory dir) {
        MAGIC_INT = Integer.MAX_VALUE / 22317;
        this.graph = g;
        this.nodeAccess = g.getNodeAccess();
        dataAccess = dir.find("location_index", DAType.getPreferredInt(dir.getDefaultType()));
    }

    public int getMinResolutionInMeter() {
        return minResolutionInMeter;
    }

    /**
     * Minimum width in meter of one tile. Decrease this if you need faster queries, but keep in
     * mind that then queries with different coordinates are more likely to fail.
     */
    public LocationIndexTree setMinResolutionInMeter(int minResolutionInMeter) {
        this.minResolutionInMeter = minResolutionInMeter;
        return this;
    }

    /**
     * Searches also neighbouring tiles until the maximum distance from the query point is reached
     * (minResolutionInMeter*regionAround). Set to 1 to only search one tile. Good if you
     * have strict performance requirements and want the search to terminate early, and you can tolerate
     * that edges that may be in neighboring tiles are not found. Default is 4, which means approximately
     * that a square of three tiles upwards, downwards, leftwards and rightwards from the tile the query tile
     * is in is searched.
     */
    public LocationIndexTree setMaxRegionSearch(int numTiles) {
        if (numTiles < 1)
            throw new IllegalArgumentException("Region of location index must be at least 1 but was " + numTiles);

        // see #232
        if (numTiles % 2 == 1)
            numTiles++;

        this.maxRegionSearch = numTiles;
        return this;
    }

    void prepareAlgo() {
        // 0.1 meter should count as 'equal'
        equalNormedDelta = distCalc.calcNormalizedDist(0.1);

        // now calculate the necessary maxDepth d for our current bounds
        // if we assume a minimum resolution like 0.5km for a leaf-tile
        // n^(depth/2) = toMeter(dLon) / minResolution
        BBox bounds = graph.getBounds();
        if (graph.getNodes() == 0)
            throw new IllegalStateException("Cannot create location index of empty graph!");

        if (!bounds.isValid())
            throw new IllegalStateException("Cannot create location index when graph has invalid bounds: " + bounds);

        double lat = Math.min(Math.abs(bounds.maxLat), Math.abs(bounds.minLat));
        double maxDistInMeter = Math.max(
                (bounds.maxLat - bounds.minLat) / 360 * DistanceCalcEarth.C,
                (bounds.maxLon - bounds.minLon) / 360 * preciseDistCalc.calcCircumference(lat));
        double tmp = maxDistInMeter / minResolutionInMeter;
        tmp = tmp * tmp;
        IntArrayList tmpEntries = new IntArrayList();
        // the last one is always 4 to reduce costs if only a single entry
        tmp /= 4;
        while (tmp > 1) {
            int tmpNo;
            if (tmp >= 16) {
                tmpNo = 16;
            } else if (tmp >= 4) {
                tmpNo = 4;
            } else {
                break;
            }
            tmpEntries.add(tmpNo);
            tmp /= tmpNo;
        }
        tmpEntries.add(4);
        initEntries(tmpEntries.toArray());
        int shiftSum = 0;
        long parts = 1;
        for (int i = 0; i < shifts.length; i++) {
            shiftSum += shifts[i];
            parts *= entries[i];
        }
        if (shiftSum > 64)
            throw new IllegalStateException("sum of all shifts does not fit into a long variable");

        keyAlgo = new SpatialKeyAlgo(shiftSum).bounds(bounds);
        parts = Math.round(Math.sqrt(parts));
        deltaLat = (bounds.maxLat - bounds.minLat) / parts;
        deltaLon = (bounds.maxLon - bounds.minLon) / parts;
    }

    private LocationIndexTree initEntries(int[] entries) {
        if (entries.length < 1) {
            // at least one depth should have been specified
            throw new IllegalStateException("depth needs to be at least 1");
        }
        this.entries = entries;
        int depth = entries.length;
        shifts = new byte[depth];
        bitmasks = new long[depth];
        int lastEntry = entries[0];
        for (int i = 0; i < depth; i++) {
            if (lastEntry < entries[i]) {
                throw new IllegalStateException("entries should decrease or stay but was:"
                        + Arrays.toString(entries));
            }
            lastEntry = entries[i];
            shifts[i] = getShift(entries[i]);
            bitmasks[i] = getBitmask(shifts[i]);
        }
        return this;
    }

    private byte getShift(int entries) {
        byte b = (byte) Math.round(Math.log(entries) / Math.log(2));
        if (b <= 0)
            throw new IllegalStateException("invalid shift:" + b);

        return b;
    }

    private long getBitmask(int shift) {
        long bm = (1L << shift) - 1;
        if (bm <= 0) {
            throw new IllegalStateException("invalid bitmask:" + bm);
        }
        return bm;
    }

    InMemConstructionIndex getPrepareInMemIndex(EdgeFilter edgeFilter) {
        InMemConstructionIndex memIndex = new InMemConstructionIndex(entries[0]);
        memIndex.prepare(edgeFilter);
        return memIndex;
    }

    @Override
    public LocationIndex setResolution(int minResolutionInMeter) {
        if (minResolutionInMeter <= 0)
            throw new IllegalStateException("Negative precision is not allowed!");

        setMinResolutionInMeter(minResolutionInMeter);
        return this;
    }

    @Override
    public LocationIndex setApproximation(boolean approx) {
        if (approx)
            distCalc = DistancePlaneProjection.DIST_PLANE;
        else
            distCalc = DistanceCalcEarth.DIST_EARTH;
        return this;
    }

    @Override
    public LocationIndexTree create(long size) {
        throw new UnsupportedOperationException("Not supported. Use prepareIndex instead.");
    }

    @Override
    public boolean loadExisting() {
        if (initialized)
            throw new IllegalStateException("Call loadExisting only once");

        if (!dataAccess.loadExisting())
            return false;

        if (dataAccess.getHeader(0) != MAGIC_INT)
            throw new IllegalStateException("incorrect location index version, expected:" + MAGIC_INT);

        if (dataAccess.getHeader(1 * 4) != calcChecksum())
            throw new IllegalStateException("location index was opened with incorrect graph: "
                    + dataAccess.getHeader(1 * 4) + " vs. " + calcChecksum());

        setMinResolutionInMeter(dataAccess.getHeader(2 * 4));
        prepareAlgo();
        initialized = true;
        return true;
    }

    @Override
    public void flush() {
        dataAccess.setHeader(0, MAGIC_INT);
        dataAccess.setHeader(1 * 4, calcChecksum());
        dataAccess.setHeader(2 * 4, minResolutionInMeter);

        // saving space not necessary: dataAccess.trimTo((lastPointer + 1) * 4);
        dataAccess.flush();
    }

    @Override
    public LocationIndex prepareIndex() {
        return prepareIndex(EdgeFilter.ALL_EDGES);
    }

    public LocationIndex prepareIndex(EdgeFilter edgeFilter) {
        if (initialized)
            throw new IllegalStateException("Call prepareIndex only once");

        StopWatch sw = new StopWatch().start();
        prepareAlgo();
        // in-memory preparation
        InMemConstructionIndex inMem = getPrepareInMemIndex(edgeFilter);

        // compact & store to dataAccess
        dataAccess.create(64 * 1024);
        try {
            inMem.store(inMem.root, START_POINTER);
            flush();
        } catch (Exception ex) {
            throw new IllegalStateException("Problem while storing location index. " + Helper.getMemInfo(), ex);
        }
        float entriesPerLeaf = (float) inMem.size / inMem.leafs;
        initialized = true;
        logger.info("location index created in " + sw.stop().getSeconds()
                + "s, size:" + Helper.nf(inMem.size)
                + ", leafs:" + Helper.nf(inMem.leafs)
                + ", precision:" + minResolutionInMeter
                + ", depth:" + entries.length
                + ", checksum:" + calcChecksum()
                + ", entries:" + Arrays.toString(entries)
                + ", entriesPerLeaf:" + entriesPerLeaf);

        return this;
    }

    int calcChecksum() {
        return graph.getNodes() ^ graph.getAllEdges().length();
    }

    @Override
    public void close() {
        dataAccess.close();
    }

    @Override
    public boolean isClosed() {
        return dataAccess.isClosed();
    }

    @Override
    public long getCapacity() {
        return dataAccess.getCapacity();
    }

    @Override
    public void setSegmentSize(int bytes) {
        dataAccess.setSegmentSize(bytes);
    }

    // just for test
    IntArrayList getEntries() {
        return IntArrayList.from(entries);
    }

    /**
     * This method fills the set with stored node IDs from the given spatial key part (a latitude-longitude prefix).
     */
    final void fillIDs(long keyPart, int intPointer, GHIntHashSet set, int depth, EdgeFilter edgeFilter) {
        long pointer = (long) intPointer << 2;
        if (depth == entries.length) {
            int nextIntPointer = dataAccess.getInt(pointer);
            if (nextIntPointer < 0) {
                // single data entries (less disc space)
                int edgeId = -(nextIntPointer + 1);
                EdgeIteratorState edgeIteratorState = graph.getEdgeIteratorStateForKey(edgeId * 2);
                if (edgeFilter.accept(edgeIteratorState)) {
                    set.add(edgeId);
                }
            } else {
                long max = (long) nextIntPointer * 4;
                // leaf entry => nextIntPointer is maxPointer
                for (long leafIndex = pointer + 4; leafIndex < max; leafIndex += 4) {
                    int edgeId = dataAccess.getInt(leafIndex);
                    EdgeIteratorState edgeIteratorState = graph.getEdgeIteratorStateForKey(edgeId * 2);
                    if (edgeFilter.accept(edgeIteratorState)) {
                        set.add(edgeId);
                    }
                }
            }
            return;
        }
        int offset = (int) (bitmasks[depth] & keyPart) << 2;
        int nextIntPointer = dataAccess.getInt(pointer + offset);
        if (nextIntPointer > 0) {
            // tree entry => negative value points to subentries
            fillIDs(keyPart >>> shifts[depth], nextIntPointer, set, depth + 1, edgeFilter);
        }
    }

    // this method returns the spatial key in reverse order for easier right-shifting
    final long createReverseKey(double lat, double lon) {
        return BitUtil.BIG.reverse(keyAlgo.encode(lat, lon), keyAlgo.getBits());
    }

    final long createReverseKey(long key) {
        return BitUtil.BIG.reverse(key, keyAlgo.getBits());
    }

    /**
     * calculate the distance to the nearest tile border for a given lat/lon coordinate in the
     * context of a spatial key tile.
     */
    final double calculateRMin(double lat, double lon) {
        return calculateRMin(lat, lon, 0);
    }

    /**
     * Calculates the distance to the nearest tile border, where the tile border is the rectangular
     * region with dimension 2*paddingTiles + 1 and where the center tile contains the given lat/lon
     * coordinate
     */
    final double calculateRMin(double lat, double lon, int paddingTiles) {
        GHPoint query = new GHPoint(lat, lon);
        long key = keyAlgo.encode(query);
        GHPoint center = new GHPoint();
        keyAlgo.decode(key, center);

        // deltaLat and deltaLon comes from the LocationIndex:
        double minLat = center.lat - (0.5 + paddingTiles) * deltaLat;
        double maxLat = center.lat + (0.5 + paddingTiles) * deltaLat;
        double minLon = center.lon - (0.5 + paddingTiles) * deltaLon;
        double maxLon = center.lon + (0.5 + paddingTiles) * deltaLon;

        double dSouthernLat = query.lat - minLat;
        double dNorthernLat = maxLat - query.lat;
        double dWesternLon = query.lon - minLon;
        double dEasternLon = maxLon - query.lon;

        // convert degree deltas into a radius in meter
        double dMinLat, dMinLon;
        if (dSouthernLat < dNorthernLat) {
            dMinLat = distCalc.calcDist(query.lat, query.lon, minLat, query.lon);
        } else {
            dMinLat = distCalc.calcDist(query.lat, query.lon, maxLat, query.lon);
        }

        if (dWesternLon < dEasternLon) {
            dMinLon = distCalc.calcDist(query.lat, query.lon, query.lat, minLon);
        } else {
            dMinLon = distCalc.calcDist(query.lat, query.lon, query.lat, maxLon);
        }

        return Math.min(dMinLat, dMinLon);
    }

    /**
     * Provide info about tilesize for testing / visualization
     */
    public double getDeltaLat() {
        return deltaLat;
    }

    public double getDeltaLon() {
        return deltaLon;
    }

    public void query(BBox queryShape, final Visitor function) {
        BBox bbox = graph.getBounds();
        final IntHashSet set = new IntHashSet();
        query(START_POINTER, queryShape,
                bbox.minLat, bbox.minLon, bbox.maxLat - bbox.minLat, bbox.maxLon - bbox.minLon,
                new Visitor() {
                    @Override
                    public boolean isTileInfo() {
                        return function.isTileInfo();
                    }

                    @Override
                    public void onTile(BBox bbox, int width) {
                        function.onTile(bbox, width);
                    }

                    @Override
                    public void onEdge(int edgeId) {
                        if (set.add(edgeId))
                            function.onEdge(edgeId);
                    }
                }, 0);
    }

    final void query(int intPointer, BBox queryBBox,
                     double minLat, double minLon,
                     double deltaLatPerDepth, double deltaLonPerDepth,
                     Visitor function, int depth) {
        long pointer = (long) intPointer << 2;
        if (depth == entries.length) {
            int nextIntPointer = dataAccess.getInt(pointer);
            if (nextIntPointer < 0) {
                // single data entries (less disc space)
                function.onEdge(-(nextIntPointer + 1));
            } else {
                long maxPointer = (long) nextIntPointer * 4;
                // loop through every leaf entry => nextIntPointer is maxPointer
                for (long leafPointer = pointer + 4; leafPointer < maxPointer; leafPointer += 4) {
                    // we could read the whole info at once via getBytes instead of getInt
                    function.onEdge(dataAccess.getInt(leafPointer));
                }
            }
            return;
        }
        int max = (1 << shifts[depth]);
        int factor = max == 4 ? 2 : 4;
        deltaLonPerDepth /= factor;
        deltaLatPerDepth /= factor;
        for (int cellIndex = 0; cellIndex < max; cellIndex++) {
            int nextIntPointer = dataAccess.getInt(pointer + cellIndex * 4);
            if (nextIntPointer <= 0)
                continue;
            // this bit magic does two things for the 4 and 16 tiles case:
            // 1. it assumes the cellIndex is a reversed spatial key and so it reverses it
            // 2. it picks every second bit (e.g. for just latitudes) and interprets the result as an integer
            int latCount = max == 4 ? (cellIndex & 1) : (cellIndex & 1) * 2 + ((cellIndex & 4) == 0 ? 0 : 1);
            int lonCount = max == 4 ? (cellIndex >> 1) : (cellIndex & 2) + ((cellIndex & 8) == 0 ? 0 : 1);
            double tmpMinLon = minLon + deltaLonPerDepth * lonCount,
                    tmpMinLat = minLat + deltaLatPerDepth * latCount;

            BBox bbox = (queryBBox != null || function.isTileInfo()) ? new BBox(tmpMinLon, tmpMinLon + deltaLonPerDepth, tmpMinLat, tmpMinLat + deltaLatPerDepth) : null;
            if (function.isTileInfo())
                function.onTile(bbox, depth);
            if (queryBBox == null || queryBBox.contains(bbox)) {
                // fill without a restriction!
                query(nextIntPointer, null, tmpMinLat, tmpMinLon, deltaLatPerDepth, deltaLonPerDepth, function, depth + 1);
            } else if (queryBBox.intersects(bbox)) {
                query(nextIntPointer, queryBBox, tmpMinLat, tmpMinLon, deltaLatPerDepth, deltaLonPerDepth, function, depth + 1);
            }
        }
    }

    /**
     * This method collects the node indices from the quad tree data structure in a certain order
     * which makes sure not too many nodes are collected as well as no nodes will be missing. See
     * discussion at issue #221.
     * <p>
     *
     * @return true if no further call of this method is required. False otherwise, ie. a next
     * iteration is necessary and no early finish possible.
     */
    final boolean findEdgeIdsInNeighborhood(double queryLat, double queryLon, GHIntHashSet foundEntries, int iteration, EdgeFilter edgeFilter) {
        // find entries in border of searchbox
        for (int yreg = -iteration; yreg <= iteration; yreg++) {
            double subqueryLat = queryLat + yreg * deltaLat;
            double subqueryLonA = queryLon - iteration * deltaLon;
            double subqueryLonB = queryLon + iteration * deltaLon;
            findNetworkEntriesSingleRegion(foundEntries, subqueryLat, subqueryLonA, edgeFilter);

            // minor optimization for iteration == 0
            if (iteration > 0)
                findNetworkEntriesSingleRegion(foundEntries, subqueryLat, subqueryLonB, edgeFilter);
        }

        for (int xreg = -iteration + 1; xreg <= iteration - 1; xreg++) {
            double subqueryLon = queryLon + xreg * deltaLon;
            double subqueryLatA = queryLat - iteration * deltaLat;
            double subqueryLatB = queryLat + iteration * deltaLat;
            findNetworkEntriesSingleRegion(foundEntries, subqueryLatA, subqueryLon, edgeFilter);
            findNetworkEntriesSingleRegion(foundEntries, subqueryLatB, subqueryLon, edgeFilter);
        }

        if (iteration % 2 != 0) {
            // Check if something was found already...
            if (!foundEntries.isEmpty()) {
                double rMin = calculateRMin(queryLat, queryLon, iteration);
                double minDistance = calcMinDistance(queryLat, queryLon, foundEntries);

                if (minDistance < rMin)
                    // early finish => foundEntries contains a nearest node for sure
                    return true;
                // else: continue as an undetected nearer node may sit in a neighbouring tile.
                // Now calculate how far we have to look outside to find any hidden nearest nodes
                // and repeat whole process with wider search area until this distance is covered.
            }
        }

        // no early finish possible
        return false;
    }

    final double calcMinDistance(double queryLat, double queryLon, GHIntHashSet edges) {
        double min = Double.MAX_VALUE;
        for (IntCursor edge : edges) {
            EdgeIteratorState edgeIteratorState = graph.getEdgeIteratorStateForKey(edge.value * 2);
            int nodeA = edgeIteratorState.getBaseNode();
            double distA = distCalc.calcDist(queryLat, queryLon, nodeAccess.getLat(nodeA), nodeAccess.getLon(nodeA));
            if (distA < min) {
                min = distA;
            }
            int nodeB = edgeIteratorState.getAdjNode();
            double distB = distCalc.calcDist(queryLat, queryLon, nodeAccess.getLat(nodeB), nodeAccess.getLon(nodeB));
            if (distB < min) {
                min = distB;
            }
        }
        return min;
    }

    final void findNetworkEntriesSingleRegion(GHIntHashSet storedNetworkEntryIds, double queryLat, double queryLon, EdgeFilter edgeFilter) {
        long keyPart = createReverseKey(queryLat, queryLon);
        fillIDs(keyPart, START_POINTER, storedNetworkEntryIds, 0, edgeFilter);
    }

    @Override
    public Snap findClosest(final double queryLat, final double queryLon, final EdgeFilter edgeFilter) {
        if (isClosed())
            throw new IllegalStateException("You need to create a new LocationIndex instance as it is already closed");

        GHIntHashSet storedNetworkEntryIds = new GHIntHashSet();
        for (int iteration = 0; iteration < maxRegionSearch; iteration++) {
            boolean earlyFinish = findEdgeIdsInNeighborhood(queryLat, queryLon, storedNetworkEntryIds, iteration, edgeFilter);
            if (earlyFinish)
                break;
        }

        final GHBitSet checkBitset = new GHTBitSet(new GHIntHashSet());
        final EdgeExplorer explorer = graph.createEdgeExplorer();
        final Snap closestMatch = new Snap(queryLat, queryLon);
        storedNetworkEntryIds.forEach((IntPredicate) edgeId -> {
            new XFirstSearchCheck(queryLat, queryLon, checkBitset, edgeFilter) {
                @Override
                protected double getQueryDistance() {
                    return closestMatch.getQueryDistance();
                }

                @Override
                protected boolean check(int node, double normedDist, int wayIndex, EdgeIteratorState edge, Snap.Position pos) {
                    if (normedDist < closestMatch.getQueryDistance()) {
                        closestMatch.setQueryDistance(normedDist);
                        closestMatch.setClosestNode(node);
                        closestMatch.setClosestEdge(edge.detach(false));
                        closestMatch.setWayIndex(wayIndex);
                        closestMatch.setSnappedPosition(pos);
                        return true;
                    }
                    return false;
                }
            }.start(explorer, graph.getEdgeIteratorStateForKey(edgeId * 2).getBaseNode());
            return true;
        });

        if (closestMatch.isValid()) {
            closestMatch.setQueryDistance(distCalc.calcDenormalizedDist(closestMatch.getQueryDistance()));
            closestMatch.calcSnappedPoint(distCalc);
        }
        return closestMatch;
    }

    // make entries static as otherwise we get an additional reference to this class (memory waste)
    interface InMemEntry {
        boolean isLeaf();
    }

    static class InMemLeafEntry extends SortedIntSet implements InMemEntry {
        // private long key;

        public InMemLeafEntry(int count, long key) {
            super(count);
            // this.key = key;
        }

        public boolean addNode(int nodeId) {
            return addOnce(nodeId);
        }

        @Override
        public final boolean isLeaf() {
            return true;
        }

        @Override
        public String toString() {
            return "LEAF " + /*key +*/ " " + super.toString();
        }

        IntArrayList getResults() {
            return this;
        }
    }

    // Space efficient sorted integer set. Suited for only a few entries.
    static class SortedIntSet extends IntArrayList {
        SortedIntSet(int capacity) {
            super(capacity);
        }

        /**
         * Allow adding a value only once
         */
        public boolean addOnce(int value) {
            int foundIndex = Arrays.binarySearch(buffer, 0, size(), value);
            if (foundIndex >= 0) {
                return false;
            }
            foundIndex = -foundIndex - 1;
            insert(foundIndex, value);
            return true;
        }
    }

    static class InMemTreeEntry implements InMemEntry {
        InMemEntry[] subEntries;

        public InMemTreeEntry(int subEntryNo) {
            subEntries = new InMemEntry[subEntryNo];
        }

        public InMemEntry getSubEntry(int index) {
            return subEntries[index];
        }

        public void setSubEntry(int index, InMemEntry subEntry) {
            this.subEntries[index] = subEntry;
        }

        public Collection<InMemEntry> getSubEntriesForDebug() {
            List<InMemEntry> list = new ArrayList<>();
            for (InMemEntry e : subEntries) {
                if (e != null) {
                    list.add(e);
                }
            }
            return list;
        }

        @Override
        public final boolean isLeaf() {
            return false;
        }

        @Override
        public String toString() {
            return "TREE";
        }
    }

    class InMemConstructionIndex {
        int size;
        int leafs;
        InMemTreeEntry root;

        public InMemConstructionIndex(int noOfSubEntries) {
            root = new InMemTreeEntry(noOfSubEntries);
        }

        void prepare(EdgeFilter edgeFilter) {
            AllEdgesIterator allIter = graph.getAllEdges();
            try {
                while (allIter.next()) {
                    if (!edgeFilter.accept(allIter))
                        continue;
                    int edge = allIter.getEdge();
                    int nodeA = allIter.getBaseNode();
                    int nodeB = allIter.getAdjNode();
                    double lat1 = nodeAccess.getLatitude(nodeA);
                    double lon1 = nodeAccess.getLongitude(nodeA);
                    double lat2;
                    double lon2;
                    PointList points = allIter.fetchWayGeometry(FetchMode.PILLAR_ONLY);
                    int len = points.getSize();
                    for (int i = 0; i < len; i++) {
                        lat2 = points.getLatitude(i);
                        lon2 = points.getLongitude(i);
                        addEdgeToAllTilesOnLine(edge, lat1, lon1, lat2, lon2);
                        lat1 = lat2;
                        lon1 = lon2;
                    }
                    lat2 = nodeAccess.getLatitude(nodeB);
                    lon2 = nodeAccess.getLongitude(nodeB);
                    addEdgeToAllTilesOnLine(edge, lat1, lon1, lat2, lon2);
                }
            } catch (Exception ex) {
                logger.error("Problem! base:" + allIter.getBaseNode() + ", adj:" + allIter.getAdjNode()
                        + ", edge:" + allIter.getEdge(), ex);
            }
        }

        void addEdgeToAllTilesOnLine(final int edgeId, final double lat1, final double lon1, final double lat2, final double lon2) {
            if (!distCalc.isCrossBoundary(lon1, lon2)) {
                // which tile am I in?
                int y1 = (int) ((lat1 - graph.getBounds().minLat) / deltaLat);
                int x1 = (int) ((lon1 - graph.getBounds().minLon) / deltaLon);
                int y2 = (int) ((lat2 - graph.getBounds().minLat) / deltaLat);
                int x2 = (int) ((lon2 - graph.getBounds().minLon) / deltaLon);
                // Find all the tiles on the line from (y1, x1) to (y2, y2) in tile coordinates (y,x)
                BresenhamLine.bresenham(y1, x1, y2, x2, (y, x) -> {
                    // Now convert tile coordinates to representative lat/lon coordinates for that tile
                    // by going toward the tile center by .1
                    double rLat = (y + .1) * deltaLat + graph.getBounds().minLat;
                    double rLon = (x + .1) * deltaLon + graph.getBounds().minLon;
                    // Now find the tile key for that representative point
                    // TODO: Do all that in one step, we know how to move up/down in tile keys!
                    long key = keyAlgo.encode(rLat, rLon);
                    long keyPart = createReverseKey(key);
                    addEdgeToOneTile(root, edgeId, 0, keyPart, key);
                });
            }
        }

        void addEdgeToOneTile(InMemEntry entry, int value, int depth, long keyPart, long key) {
            if (entry.isLeaf()) {
                InMemLeafEntry leafEntry = (InMemLeafEntry) entry;
                leafEntry.addNode(value);
            } else {
                int index = (int) (bitmasks[depth] & keyPart);
                keyPart = keyPart >>> shifts[depth];
                InMemTreeEntry treeEntry = ((InMemTreeEntry) entry);
                InMemEntry subentry = treeEntry.getSubEntry(index);
                depth++;
                if (subentry == null) {
                    if (depth == entries.length) {
                        subentry = new InMemLeafEntry(initSizeLeafEntries, key);
                    } else {
                        subentry = new InMemTreeEntry(entries[depth]);
                    }
                    treeEntry.setSubEntry(index, subentry);
                }
                addEdgeToOneTile(subentry, value, depth, keyPart, key);
            }
        }

        // store and freezes tree
        int store(InMemEntry entry, int intPointer) {
            long pointer = (long) intPointer * 4;
            if (entry.isLeaf()) {
                InMemLeafEntry leaf = ((InMemLeafEntry) entry);
                IntArrayList entries = leaf.getResults();
                int len = entries.size();
                if (len == 0) {
                    return intPointer;
                }
                size += len;
                intPointer++;
                leafs++;
                dataAccess.ensureCapacity((long) (intPointer + len + 1) * 4);
                if (len == 1) {
                    // less disc space for single entries
                    dataAccess.setInt(pointer, -entries.get(0) - 1);
                } else {
                    for (int index = 0; index < len; index++, intPointer++) {
                        dataAccess.setInt((long) intPointer * 4, entries.get(index));
                    }
                    dataAccess.setInt(pointer, intPointer);
                }
            } else {
                InMemTreeEntry treeEntry = ((InMemTreeEntry) entry);
                int len = treeEntry.subEntries.length;
                intPointer += len;
                for (int subCounter = 0; subCounter < len; subCounter++, pointer += 4) {
                    InMemEntry subEntry = treeEntry.subEntries[subCounter];
                    if (subEntry == null) {
                        continue;
                    }
                    dataAccess.ensureCapacity((long) (intPointer + 1) * 4);
                    int prevIntPointer = intPointer;
                    intPointer = store(subEntry, prevIntPointer);
                    if (intPointer == prevIntPointer) {
                        dataAccess.setInt(pointer, 0);
                    } else {
                        dataAccess.setInt(pointer, prevIntPointer);
                    }
                }
            }
            return intPointer;
        }
    }

    /**
     * Make it possible to collect nearby location also for other purposes.
     */
    protected abstract class XFirstSearchCheck extends BreadthFirstSearch {
        final double queryLat;
        final double queryLon;
        final GHBitSet checkBitset;
        final EdgeFilter edgeFilter;
        boolean goFurther = true;
        double currNormedDist;
        double currLat;
        double currLon;
        int currNode;

        public XFirstSearchCheck(double queryLat, double queryLon, GHBitSet checkBitset, EdgeFilter edgeFilter) {
            this.queryLat = queryLat;
            this.queryLon = queryLon;
            this.checkBitset = checkBitset;
            this.edgeFilter = edgeFilter;
        }

        @Override
        protected GHBitSet createBitSet() {
            return checkBitset;
        }

        @Override
        protected boolean goFurther(int baseNode) {
            currNode = baseNode;
            currLat = nodeAccess.getLatitude(baseNode);
            currLon = nodeAccess.getLongitude(baseNode);
            currNormedDist = distCalc.calcNormalizedDist(queryLat, queryLon, currLat, currLon);
            return goFurther;
        }

        @Override
        protected boolean checkAdjacent(EdgeIteratorState currEdge) {
            goFurther = false;
            if (!edgeFilter.accept(currEdge)) {
                // only limit the adjNode to a certain radius as currNode could be the wrong side of a valid edge
                // goFurther = currDist < minResolution2InMeterNormed;
                return true;
            }

            int tmpClosestNode = currNode;
            if (check(tmpClosestNode, currNormedDist, 0, currEdge, Snap.Position.TOWER)) {
                if (currNormedDist <= equalNormedDelta)
                    return false;
            }

            int adjNode = currEdge.getAdjNode();
            double adjLat = nodeAccess.getLatitude(adjNode);
            double adjLon = nodeAccess.getLongitude(adjNode);
            double adjDist = distCalc.calcNormalizedDist(adjLat, adjLon, queryLat, queryLon);
            // if there are wayPoints this is only an approximation
            if (adjDist < currNormedDist)
                tmpClosestNode = adjNode;

            double tmpLat = currLat;
            double tmpLon = currLon;
            double tmpNormedDist;
            PointList pointList = currEdge.fetchWayGeometry(FetchMode.PILLAR_AND_ADJ);
            int len = pointList.getSize();
            for (int pointIndex = 0; pointIndex < len; pointIndex++) {
                double wayLat = pointList.getLatitude(pointIndex);
                double wayLon = pointList.getLongitude(pointIndex);
                Snap.Position pos = Snap.Position.EDGE;
                if (distCalc.isCrossBoundary(tmpLon, wayLon)) {
                    tmpLat = wayLat;
                    tmpLon = wayLon;
                    continue;
                }

                if (distCalc.validEdgeDistance(queryLat, queryLon, tmpLat, tmpLon, wayLat, wayLon)) {
                    tmpNormedDist = distCalc.calcNormalizedEdgeDistance(queryLat, queryLon,
                            tmpLat, tmpLon, wayLat, wayLon);
                    check(tmpClosestNode, tmpNormedDist, pointIndex, currEdge, pos);
                } else {
                    if (pointIndex + 1 == len) {
                        tmpNormedDist = adjDist;
                        pos = Snap.Position.TOWER;
                    } else {
                        tmpNormedDist = distCalc.calcNormalizedDist(queryLat, queryLon, wayLat, wayLon);
                        pos = Snap.Position.PILLAR;
                    }
                    check(tmpClosestNode, tmpNormedDist, pointIndex + 1, currEdge, pos);
                }

                if (tmpNormedDist <= equalNormedDelta)
                    return false;

                tmpLat = wayLat;
                tmpLon = wayLon;
            }
            return getQueryDistance() > equalNormedDelta;
        }

        protected abstract double getQueryDistance();

        protected abstract boolean check(int node, double normedDist, int wayIndex, EdgeIteratorState iter, Snap.Position pos);
    }
}
