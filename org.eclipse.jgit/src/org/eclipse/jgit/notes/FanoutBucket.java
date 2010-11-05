/*
 * Copyright (C) 2010, Google Inc.
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.notes;

import static org.eclipse.jgit.lib.FileMode.TREE;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.TreeFormatter;

/**
 * A note tree holding only note subtrees, each named using a 2 digit hex name.
 *
 * The fanout buckets/trees contain on average 256 subtrees, naming the subtrees
 * by a slice of the ObjectId contained within them, from "00" through "ff".
 *
 * Each fanout bucket has a {@link #prefixLen} that defines how many digits it
 * skips in an ObjectId before it gets to the digits matching {@link #table}.
 *
 * The root tree has {@code prefixLen == 0}, and thus does not skip any digits.
 * For ObjectId "c0ffee...", the note (if it exists) will be stored within the
 * bucket {@code table[0xc0]}.
 *
 * The first level tree has {@code prefixLen == 2}, and thus skips the first two
 * digits. For the same example "c0ffee..." object, its note would be found
 * within the {@code table[0xff]} bucket (as first 2 digits "c0" are skipped).
 *
 * Each subtree is loaded on-demand, reducing startup latency for reads that
 * only need to examine a few objects. However, due to the rather uniform
 * distribution of the SHA-1 hash that is used for ObjectIds, accessing 256
 * objects is very likely to load all of the subtrees into memory.
 *
 * A FanoutBucket must be parsed from a tree object by {@link NoteParser}.
 */
class FanoutBucket extends InMemoryNoteBucket {
	/**
	 * Fan-out table similar to the PackIndex structure.
	 *
	 * Notes for an object are stored within the sub-bucket that is held here as
	 * {@code table[ objectId.getByte( prefixLen / 2 ) ]}. If the slot is null
	 * there are no notes with that prefix.
	 */
	private final NoteBucket[] table;

	/** Number of non-null slots in {@link #table}. */
	private int cnt;

	FanoutBucket(int prefixLen) {
		super(prefixLen);
		table = new NoteBucket[256];
	}

	void parseOneEntry(int cell, ObjectId id) {
		table[cell] = new LazyNoteBucket(id);
		cnt++;
	}

	@Override
	ObjectId get(AnyObjectId objId, ObjectReader or) throws IOException {
		NoteBucket b = table[cell(objId)];
		return b != null ? b.get(objId, or) : null;
	}

	@Override
	Iterator<Note> iterator(AnyObjectId objId, final ObjectReader reader)
			throws IOException {
		final MutableObjectId id = new MutableObjectId();
		id.fromObjectId(objId);

		return new Iterator<Note>() {
			private int cell;

			private Iterator<Note> itr;

			public boolean hasNext() {
				if (itr != null && itr.hasNext())
					return true;

				for (; cell < table.length; cell++) {
					NoteBucket b = table[cell];
					if (b == null)
						continue;

					try {
						id.setByte(prefixLen >> 1, cell);
						itr = b.iterator(id, reader);
					} catch (IOException err) {
						throw new RuntimeException(err);
					}

					if (itr.hasNext()) {
						cell++;
						return true;
					}
				}
				return false;
			}

			public Note next() {
				if (hasNext())
					return itr.next();
				else
					throw new NoSuchElementException();
			}

			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}

	@Override
	InMemoryNoteBucket set(AnyObjectId noteOn, AnyObjectId noteData,
			ObjectReader or) throws IOException {
		int cell = cell(noteOn);
		NoteBucket b = table[cell];

		if (b == null) {
			if (noteData == null)
				return this;

			LeafBucket n = new LeafBucket(prefixLen + 2);
			table[cell] = n.set(noteOn, noteData, or);
			cnt++;
			return this;

		} else {
			NoteBucket n = b.set(noteOn, noteData, or);
			if (n == null) {
				table[cell] = null;
				cnt--;

				if (cnt == 0)
					return null;

				return this;

			} else if (n != b) {
				table[cell] = n;
			}
			return this;
		}
	}

	private static final byte[] hexchar = { '0', '1', '2', '3', '4', '5', '6',
			'7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

	@Override
	ObjectId writeTree(ObjectInserter inserter) throws IOException {
		byte[] nameBuf = new byte[2];
		TreeFormatter fmt = new TreeFormatter(treeSize());
		NonNoteEntry e = nonNotes;

		for (int cell = 0; cell < 256; cell++) {
			NoteBucket b = table[cell];
			if (b == null)
				continue;

			nameBuf[0] = hexchar[cell >>> 4];
			nameBuf[1] = hexchar[cell & 0x0f];

			while (e != null && e.pathCompare(nameBuf, 0, 2, TREE) < 0) {
				e.format(fmt);
				e = e.next;
			}

			fmt.append(nameBuf, 0, 2, TREE, b.writeTree(inserter));
		}

		for (; e != null; e = e.next)
			e.format(fmt);
		return fmt.insert(inserter);
	}

	private int treeSize() {
		int sz = cnt * TreeFormatter.entrySize(TREE, 2);
		for (NonNoteEntry e = nonNotes; e != null; e = e.next)
			sz += e.treeEntrySize();
		return sz;
	}

	@Override
	InMemoryNoteBucket append(Note note) {
		int cell = cell(note);
		InMemoryNoteBucket b = (InMemoryNoteBucket) table[cell];

		if (b == null) {
			LeafBucket n = new LeafBucket(prefixLen + 2);
			table[cell] = n.append(note);
			cnt++;

		} else {
			InMemoryNoteBucket n = b.append(note);
			if (n != b)
				table[cell] = n;
		}
		return this;
	}

	private int cell(AnyObjectId id) {
		return id.getByte(prefixLen >> 1);
	}

	private class LazyNoteBucket extends NoteBucket {
		private final ObjectId treeId;

		LazyNoteBucket(ObjectId treeId) {
			this.treeId = treeId;
		}

		@Override
		ObjectId get(AnyObjectId objId, ObjectReader or) throws IOException {
			return load(objId, or).get(objId, or);
		}

		@Override
		Iterator<Note> iterator(AnyObjectId objId, ObjectReader reader)
				throws IOException {
			return load(objId, reader).iterator(objId, reader);
		}

		@Override
		InMemoryNoteBucket set(AnyObjectId noteOn, AnyObjectId noteData,
				ObjectReader or) throws IOException {
			return load(noteOn, or).set(noteOn, noteData, or);
		}

		@Override
		ObjectId writeTree(ObjectInserter inserter) {
			return treeId;
		}

		private NoteBucket load(AnyObjectId objId, ObjectReader or)
				throws IOException {
			AbbreviatedObjectId p = objId.abbreviate(prefixLen + 2);
			NoteBucket self = NoteParser.parse(p, treeId, or);
			table[cell(objId)] = self;
			return self;
		}
	}
}
