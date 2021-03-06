/*
 * Copyright (C) 2012-2013, Robin Rosenberg <robin.rosenberg@dewire.com>
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

package org.eclipse.jgit.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FSJava7Test {
	private File trash;

	@Before
	public void setUp() throws Exception {
		trash = File.createTempFile("tmp_", "");
		trash.delete();
		assertTrue("mkdir " + trash, trash.mkdir());
	}

	@After
	public void tearDown() throws Exception {
		FileUtils.delete(trash, FileUtils.RECURSIVE | FileUtils.RETRY);
	}

	/**
	 * The old File methods traverse symbolic links and look at the targets.
	 * With symbolic links we usually want to modify/look at the link. For some
	 * reason the executable attribute seems to always look at the target, but
	 * for the other attributes like lastModified, hidden and exists we must
	 * differ between the link and the target.
	 *
	 * @throws IOException
	 * @throws InterruptedException
	 */
	@Test
	public void testSymlinkAttributes() throws IOException, InterruptedException {
		FS fs = FS.DETECTED;
		File link = new File(trash, "??");
		File target = new File(trash, "??");
		fs.createSymLink(link, "??");
		assertTrue(fs.exists(link));
		String targetName = fs.readSymLink(link);
		assertEquals("??", targetName);
		assertTrue(fs.lastModified(link) > 0);
		assertTrue(fs.exists(link));
		assertFalse(fs.canExecute(link));
		assertEquals(2, fs.length(link));
		assertFalse(fs.exists(target));
		assertFalse(fs.isFile(target));
		assertFalse(fs.isDirectory(target));
		assertFalse(fs.canExecute(target));

		RepositoryTestCase.fsTick(link);
		// Now create the link target
		FileUtils.createNewFile(target);
		assertTrue(fs.exists(link));
		assertTrue(fs.lastModified(link) > 0);
		assertTrue(fs.lastModified(target) > fs.lastModified(link));
		assertFalse(fs.canExecute(link));
		fs.setExecute(target, true);
		assertFalse(fs.canExecute(link));
		assertTrue(fs.canExecute(target));
	}

	@Test
	public void testExecutableAttributes() throws Exception {
		FS fs = FS.DETECTED;
		// If this assumption fails the test is halted and ignored.
		assumeTrue(fs instanceof FS_POSIX_Java7);

		File f = new File(trash, "bla");
		assertTrue(f.createNewFile());
		assertFalse(fs.canExecute(f));

		String umask = readUmask();
		assumeNotNull(umask);

		char others = umask.charAt(umask.length() - 1);

		boolean badUmask;
		if (others != '0' && others != '2' && others != '4' && others != '6') {
			// umask is set in the way that "others" can not "execute" => git
			// CLI will not set "execute" attribute for "others", so we also
			// don't care
			badUmask = true;
		} else {
			badUmask = false;
		}

		Set<PosixFilePermission> permissions = readPermissions(f);
		assertTrue(!permissions.contains(PosixFilePermission.OTHERS_EXECUTE));
		assertTrue(!permissions.contains(PosixFilePermission.GROUP_EXECUTE));
		assertTrue(!permissions.contains(PosixFilePermission.OWNER_EXECUTE));

		fs.setExecute(f, true);

		permissions = readPermissions(f);
		assertTrue("'owner' execute permission not set",
				permissions.contains(PosixFilePermission.OWNER_EXECUTE));
		assertTrue("'group' execute permission not set",
				permissions.contains(PosixFilePermission.GROUP_EXECUTE));
		if (badUmask) {
			assertFalse("'others' execute permission set",
					permissions.contains(PosixFilePermission.OTHERS_EXECUTE));
			System.err.println("WARNING: your system's umask: \"" + umask
					+ "\" doesn't allow FSJava7Test to test if setting posix"
					+ "  permissions for \"others\" works properly");
			assumeFalse(badUmask);
		} else {
			assertTrue("'others' execute permission not set",
					permissions.contains(PosixFilePermission.OTHERS_EXECUTE));
		}
	}

	private String readUmask() throws Exception {
		Process p = Runtime.getRuntime().exec(
				new String[] { "sh", "-c", "umask" }, null, null);
		final BufferedReader lineRead = new BufferedReader(
				new InputStreamReader(p.getInputStream(), Charset
						.defaultCharset().name()));
		p.waitFor();
		return lineRead.readLine();
	}

	private Set<PosixFilePermission> readPermissions(File f) throws IOException {
		return Files
				.getFileAttributeView(f.toPath(), PosixFileAttributeView.class)
				.readAttributes().permissions();
	}

}
