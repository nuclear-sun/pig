/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pig.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.TreeSet;
import java.util.Arrays;
import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.apache.pig.impl.eval.EvalSpec;
import org.apache.pig.impl.util.PigLogger;



/**
 * An unordered collection of Tuples with no multiples.  Data is
 * stored without duplicates as it comes in.  When it is time to spill,
 * that data is sorted and written to disk.  It must also be sorted upon
 * the first read, otherwise if a spill happened after that the iterators
 * would have no way to find their place in the new file.  The data is
 * stored in a HashSet.  When it is time to sort it is placed in an
 * ArrayList and then sorted.  Dispite all these machinations, this was
 * found to be faster than storing it in a TreeSet.
 */
public class DistinctDataBag extends DefaultAbstractBag {
    private static TupleFactory gTupleFactory = TupleFactory.getInstance();

    public DistinctDataBag() {
        mContents = new HashSet<Tuple>();
    }

    @Override
    public boolean isSorted() {
        return false;
    }
    
    @Override
    public boolean isDistinct() {
        return true;
    }
    
    @Override
    public Iterator<Tuple> iterator() {
        return new DistinctDataBagIterator();
    }

    @Override
    public void add(Tuple t) {
        synchronized (mContents) {
            if (mContents.add(t)) {
                mSize++;
            }
        }
    }

    @Override
    public void addAll(DataBag b) {
        synchronized (mContents) {
            mSize += b.size();
            Iterator<Tuple> i = b.iterator();
            while (i.hasNext()) {
                if (mContents.add(i.next())) {
                    mSize++;
                }
            }
        }
    }


    public long spill() {
        // Make sure we have something to spill.  Don't create empty
        // files, as that will make a mess.
        if (mContents.size() == 0) return 0;

        // Lock the container before I spill, so that iterators aren't
        // trying to read while I'm mucking with the container.
        long spilled = 0;
        synchronized (mContents) {
            try {
                DataOutputStream out = getSpillFile();
                // If we've already started reading, then it will already be
                // sorted into an array list.  If not, we need to sort it
                // before writing.
                if (mContents instanceof ArrayList) {
                    Iterator<Tuple> i = mContents.iterator();
                    while (i.hasNext()) {
                        i.next().write(out);
                        spilled++;
                        // This will spill every 16383 records.
                        if ((spilled & 0x3fff) == 0) reportProgress();
                    }
                } else {
                    Tuple[] array = new Tuple[mContents.size()];
                    mContents.toArray(array);
                    Arrays.sort(array);
                    for (int i = 0; i < array.length; i++) {
                        array[i].write(out);
                        spilled++;
                        // This will spill every 16383 records.
                        if ((spilled & 0x3fff) == 0) reportProgress();
                    }
                }
                out.flush();
            } catch (IOException ioe) {
                // Remove the last file from the spilled array, since we failed to
                // write to it.
                mSpillFiles.remove(mSpillFiles.size() - 1);
                PigLogger.getLogger().error(
                    "Unable to spill contents to disk", ioe);
                return 0;
            }
            mContents.clear();
        }
        return spilled;
    }

    /**
     * An iterator that handles getting the next tuple from the bag.  This
     * iterator has a couple of issues to deal with.  First, data can be
     * stored in a combination of in memory and on disk.  Second, the bag
     * may be asked to spill while the iterator is reading it.  This means
     * that it will be pointing to someplace in memory and suddenly it
     * will need to switch to a disk file.
     */
    private class DistinctDataBagIterator implements Iterator<Tuple> {

        private class TContainer implements Comparable<TContainer> {
            public Tuple tuple;
            public int fileNum;

            public int compareTo(TContainer other) {
                return tuple.compareTo(other.tuple);
            }
        }

        // We have to buffer a tuple because there's no easy way for next
        // to tell whether or not there's another tuple available, other
        // than to read it.
        private Tuple mBuf = null;
        private int mMemoryPtr = 0;
        private TreeSet<TContainer> mMergeTree = null;
        private ArrayList<DataInputStream> mStreams = null;
        private int mCntr = 0;

        DistinctDataBagIterator() {
            // If this is the first read, we need to sort the data.
            synchronized (mContents) {
                if (mContents instanceof HashSet) {
                    preMerge();
                    // We're the first reader, we need to sort the data.
                    // This is in case it gets dumped under us.
                    ArrayList<Tuple> l = new ArrayList<Tuple>(mContents);
                    Collections.sort(l);
                    mContents = l;
                }
            }
        }

        public boolean hasNext() { 
            // See if we can find a tuple.  If so, buffer it.
            mBuf = next();
            return mBuf != null;
        }

        public Tuple next() {
            // This will report progress every 1024 times through next.
            // This should be much faster than using mod.
            if ((mCntr++ & 0x3ff) == 0) reportProgress();

            // If there's one in the buffer, use that one.
            if (mBuf != null) {
                Tuple t = mBuf;
                mBuf = null;
                return t;
            }

            // Check to see if we just need to read from memory.
            boolean spilled = false;
            synchronized (mContents) {
                if (mSpillFiles == null || mSpillFiles.size() == 0) {
                    return readFromMemory();
                }

                if (mMemoryPtr > 0 && mContents.size() == 0) {
                    spilled = true;
                }
            }

            // Check to see if we were reading from memory but we spilled
            if (spilled) {
                DataInputStream in;
                // We need to open the new file
                // and then fast forward past all of the tuples we've
                // already read.  Then we need to place the first tuple
                // from that file in the priority queue.  Whatever tuples
                // from memory that were already in the queue will be fine,
                // as they're guaranteed to be ahead of the point we fast
                // foward to.
                // We're guaranteed that the file we want to read from for
                // the fast forward is the last element in mSpillFiles,
                // because we don't support calls to add() after calls to
                // iterator(), and spill() won't create empty files.
                try {
                    in = new DataInputStream(new BufferedInputStream(
                        new FileInputStream(mSpillFiles.get(
                                mSpillFiles.size() - 1))));
                    if (mStreams == null) {
                        mMergeTree = new TreeSet<TContainer>();
                        // We didn't have any files before this spill.
                        mStreams = new ArrayList<DataInputStream>(1);
                    }
                    mStreams.add(in);
                } catch (FileNotFoundException fnfe) {
                    // We can't find our own spill file?  That should never
                    // happen.
                    PigLogger.getLogger().fatal(
                        "Unable to find our spill file", fnfe);
                    throw new RuntimeException(fnfe);
                }

                // Fast foward past the tuples we've already put in the
                // queue.
                Tuple t = gTupleFactory.newTuple();
                for (int i = 0; i < mMemoryPtr; i++) {
                    try {
                        t.readFields(in);
                    } catch (EOFException eof) {
                        // This should never happen, it means we
                        // didn't dump all of our tuples to disk.
                        throw new RuntimeException("Ran out of tuples to read prematurely.");
                    } catch (IOException ioe) {
                        PigLogger.getLogger().fatal(
                            "Unable to read our spill file", ioe);
                        throw new RuntimeException(ioe);
                    }
                }
                mMemoryPtr = 0;
                // Add the next tuple from this file to the queue.
                addToQueue(null, mSpillFiles.size() - 1);
                // Fall through to read the next entry from the priority
                // queue.
            }

            // We have spill files, so we need to read the next tuple from
            // one of those files or from memory.
            return readFromTree();
        }

        /**
         * Not implemented.
         */
        public void remove() {}

        private Tuple readFromTree() {
            if (mMergeTree == null) {
                // First read, we need to set up the queue and the array of
                // file streams
                mMergeTree = new TreeSet<TContainer>();

                // Add one to the size in case we spill later.
                mStreams =
                    new ArrayList<DataInputStream>(mSpillFiles.size() + 1);

                Iterator<File> i = mSpillFiles.iterator();
                while (i.hasNext()) {
                    try {
                        DataInputStream in = 
                            new DataInputStream(new BufferedInputStream(
                                new FileInputStream(i.next())));
                        mStreams.add(in);
                        // Add the first tuple from this file into the
                        // merge queue.
                        addToQueue(null, mStreams.size() - 1);
                    } catch (FileNotFoundException fnfe) {
                        // We can't find our own spill file?  That should
                        // never happen.
                        PigLogger.getLogger().fatal(
                            "Unable to find out spill file.", fnfe);
                        throw new RuntimeException(fnfe);
                    }
                }

                // Prime one from memory too
                if (mContents.size() > 0) {
                    addToQueue(null, -1);
                }
            }

            if (mMergeTree.size() == 0) return null;

            // Pop the top one off the queue
            TContainer c = mMergeTree.first();
            mMergeTree.remove(c);

            // Add the next tuple from whereever we read from into the
            // queue.  Buffer the tuple we're returning, as we'll be
            // reusing c.
            Tuple t = c.tuple;
            addToQueue(c, c.fileNum);

            return t;
        }

        private void addToQueue(TContainer c, int fileNum) {
            if (c == null) {
                c = new TContainer();
            }
            c.fileNum = fileNum;

            if (fileNum == -1) {
                // Need to read from memory.  We may have spilled since
                // this tuple was put in the queue, and hence memory might
                // be empty.  But I don't care, as then I just won't add
                // any more from memory.
                synchronized (mContents) {
                    do {
                        c.tuple = readFromMemory();
                        if (c.tuple != null) {
                            // If we find a unique entry, then add it to the queue.
                            // Otherwise ignore it and keep reading.
                            if (mMergeTree.add(c)) {
                                return;
                            }
                        }
                    } while (c.tuple != null);
                }
                return;
            }

            // Read the next tuple from the indicated file
            DataInputStream in = mStreams.get(fileNum);
            if (in != null) {
                // There's still data in this file
                c.tuple = gTupleFactory.newTuple();
                do {
                    try {
                        c.tuple.readFields(in);
                        // If we find a unique entry, then add it to the queue.
                        // Otherwise ignore it and keep reading.  If we run out
                        // of tuples to read that's fine, we just won't add a
                        // new one from this file.
                        if (mMergeTree.add(c)) {
                            return;
                        }
                    } catch (EOFException eof) {
                        // Out of tuples in this file.  Set our slot in the
                        // array to null so we don't keep trying to read from
                        // this file.
                        mStreams.set(fileNum, null);
                        return;
                    } catch (IOException ioe) {
                        PigLogger.getLogger().fatal(
                            "Unable to read our spill file", ioe);
                        throw new RuntimeException(ioe);
                    }
                } while (true);
            }
        }

        // Function assumes that the reader lock is already held before we enter
        // this function.
        private Tuple readFromMemory() {
            if (mContents.size() == 0) return null;

            if (mMemoryPtr < mContents.size()) {
                return ((ArrayList<Tuple>)mContents).get(mMemoryPtr++);
            } else {
                return null;
            }
        }

        /**
         * Pre-merge if there are too many spill files.  This avoids the issue
         * of having too large a fan out in our merge.  Experimentation by
         * the hadoop team has shown that 100 is about the optimal number
         * of spill files.  This function modifies the mSpillFiles array
         * and assumes the write lock is already held. It will not unlock it.
         *
         * Tuples are reconstituted as tuples, evaluated, and rewritten as
         * tuples.  This is expensive, but I don't know how to read tuples
         * from the file otherwise.
         *
         * This function is slightly different than the one in
         * SortedDataBag, as it uses a TreeSet instead of a PriorityQ.
         */
        private void preMerge() {
            if (mSpillFiles == null ||
                    mSpillFiles.size() <= MAX_SPILL_FILES) {
                return;
            }

            // While there are more than max spill files, gather max spill
            // files together and merge them into one file.  Then remove the others
            // from mSpillFiles.  The new spill files are attached at the
            // end of the list, so I can just keep going until I get a
            // small enough number without too much concern over uneven
            // size merges.  Convert mSpillFiles to a linked list since
            // we'll be removing pieces from the middle and we want to do
            // it efficiently.
            try {
                LinkedList<File> ll = new LinkedList<File>(mSpillFiles);
                while (ll.size() > MAX_SPILL_FILES) {
                    ListIterator<File> i = ll.listIterator();
                    mStreams =
                        new ArrayList<DataInputStream>(MAX_SPILL_FILES);
                    mMergeTree = new TreeSet<TContainer>();

                    for (int j = 0; j < MAX_SPILL_FILES; j++) {
                        try {
                            DataInputStream in =
                                new DataInputStream(new BufferedInputStream(
                                    new FileInputStream(i.next())));
                            mStreams.add(in);
                            addToQueue(null, mStreams.size() - 1);
                            i.remove();
                        } catch (FileNotFoundException fnfe) {
                            // We can't find our own spill file?  That should
                            // neer happen.
                            PigLogger.getLogger().fatal(
                                "Unable to find out spill file.", fnfe);
                            throw new RuntimeException(fnfe);
                        }
                    }

                    // Get a new spill file.  This adds one to the end of
                    // the spill files list.  So I need to append it to my
                    // linked list as well so that it's still there when I
                    // move my linked list back to the spill files.
                    try {
                        DataOutputStream out = getSpillFile();
                        ll.add(mSpillFiles.get(mSpillFiles.size() - 1));
                        Tuple t;
                        while ((t = readFromTree()) != null) {
                            t.write(out);
                        }
                        out.flush();
                    } catch (IOException ioe) {
                        PigLogger.getLogger().fatal(
                            "Unable to read our spill file", ioe);
                        throw new RuntimeException(ioe);
                    }
                }

                // Now, move our new list back to the spill files array.
                mSpillFiles = new ArrayList<File>(ll);
            } finally {
                // Reset mStreams and mMerge so that they'll be allocated
                // properly for regular merging.
                mStreams = null;
                mMergeTree = null;
            }
        }
    }
    
}

