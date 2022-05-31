/**
 * Copyright (c) 2019 Red Hat Inc., and others
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * - Mickael Istria (Red Hat Inc.)
 */
package org.eclipse.tm4e.ui.internal.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.text.Document;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.model.IModelTokensChangedListener;
import org.eclipse.tm4e.core.model.ModelTokensChangedEvent;
import org.eclipse.tm4e.core.model.Range;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.Registry;
import org.junit.function.ThrowingRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(value = MethodOrderer.MethodName.class)
class DocumentTMModelTest {

	private static final String LF = "\n";
	private static IGrammar grammar;

	@BeforeAll
	static void beforeAll() {
		final var grammarPath = "/grammars/TypeScript.tmLanguage.json";
		final var grammarSource = DocumentTMModelTest.class.getResource(grammarPath) != null
				? IGrammarSource.fromResource(DocumentTMModelTest.class, grammarPath)
				: IGrammarSource.fromFile(Path.of("." + grammarPath));
		grammar = new Registry().addGrammar(grammarSource);
	}

	private final Document doc = new Document();
	private final TMDocumentModel model = new TMDocumentModel(doc);

	private void assertDocHasLines(final Iterable<String> lines) {
		assertEquals(String.join(LF, lines), doc.get());
	}

	private void assertRange(final ModelTokensChangedEvent e, final int startLineNumber, final int endLineNumber) {
		assertEquals(List.of(new Range(startLineNumber, endLineNumber)), e.ranges);
	}

	private ModelTokensChangedEvent awaitModelChangedEvent(final TMDocumentModel model, final List<String> initialLines,
			final ThrowingRunnable action) throws Throwable {

		// prepare document
		{
			final var signal = new CountDownLatch(1);
			final IModelTokensChangedListener listener = e -> signal.countDown();
			model.addModelTokensChangedListener(listener);
			model.getDocument().set(String.join(LF, initialLines));
			assertTrue(signal.await(2, TimeUnit.SECONDS));
			model.removeModelTokensChangedListener(listener);
		}

		// test
		final var event = new AtomicReference<ModelTokensChangedEvent>();
		final var signal = new CountDownLatch(1);
		final IModelTokensChangedListener listener = e -> {
			event.set(e);
			signal.countDown();
		};
		model.addModelTokensChangedListener(listener);
		action.run();
		assertTrue(signal.await(2, TimeUnit.SECONDS));
		model.removeModelTokensChangedListener(listener);
		return event.get();
	}

	@BeforeEach
	void setup() {
		model.setGrammar(grammar);
	}

	@AfterEach
	void tearDown() {
		model.dispose();
	}

	/**
	 * Tests inserting some chars into a line
	 */
	@Test
	void testInsertText() throws Throwable {
		final var initialLines = List.of(
				"//comment1",
				"//comment2",
				"//comment3");

		final var event = awaitModelChangedEvent(model, initialLines,
				() -> doc.replace(doc.getLineOffset(1) + 2, 0, "XY"));

		final var expectedLines = new ArrayList<>(initialLines);
		expectedLines.set(1, "//XYcomment2");
		assertDocHasLines(expectedLines);

		assertRange(event, 2, 2);
	}

	/**
	 * Tests inserting a line after the first line
	 */
	@Test
	void testInsert1Line() throws Throwable {
		final var initialLines = List.of(
				"//comment1",
				"//comment2",
				"//comment3");

		final var event = awaitModelChangedEvent(model, initialLines,
				() -> doc.replace(doc.getLineOffset(1), 0, "//commentX" + LF));

		final var expectedLines = new ArrayList<>(initialLines);
		expectedLines.add(1, "//commentX");
		assertDocHasLines(expectedLines);

		assertRange(event, 2, 2);
	}

	/**
	 * Tests inserting a line after the first line
	 */
	@Test
	void testInsert2Lines() throws Throwable {
		final var initialLines = List.of(
				"//comment1",
				"//comment2",
				"//comment3");

		final var event = awaitModelChangedEvent(model, initialLines,
				() -> doc.replace(doc.getLineOffset(1), 0, "//commentX" + LF + "//commentY" + LF));

		final var expectedLines = new ArrayList<>(initialLines);
		expectedLines.add(1, "//commentX");
		expectedLines.add(2, "//commentY");
		assertDocHasLines(expectedLines);

		assertRange(event, 2, 3);
	}

	/**
	 * Tests inserting a line after the first line
	 */
	@Test
	void testInsert2LinesPartially() throws Throwable {
		final var initialLines = List.of(
				"//comment1",
				"//comment2",
				"//comment3");

		final var event = awaitModelChangedEvent(model, initialLines,
				() -> doc.replace(doc.getLineOffset(1) + 2, 0, "X" + LF + "//Y"));

		final var expectedLines = new ArrayList<>(initialLines);
		expectedLines.add(1, "//X");
		expectedLines.set(2, "//Ycomment2");
		assertDocHasLines(expectedLines);

		assertRange(event, 2, 3);
	}

	/**
	 * Tests inserting '/*' at the beginning of the second line which invalidates all following lines
	 */
	@Test
	void testInsertMultiLineCommentStartToken() throws Throwable {
		final var initialLines = List.of(
				"//comment1",
				"//comment2",
				"//comment3",
				"//comment4");

		final var event = awaitModelChangedEvent(model, initialLines,
				() -> doc.replace(doc.getLineOffset(1), 0, "/*"));

		final var expectedLines = new ArrayList<>(initialLines);
		expectedLines.set(1, "/*" + initialLines.get(1));
		assertDocHasLines(expectedLines);

		assertRange(event, 2, doc.getNumberOfLines());
	}

	/**
	 * Tests removing the second line
	 */
	@Test
	void testRemove1Line() throws Throwable {
		final var initialLines = List.of(
				"//comment1",
				"//comment2",
				"//comment3");

		final var event = awaitModelChangedEvent(model, initialLines,
				() -> doc.replace(
						doc.getLineOffset(1),
						initialLines.get(1).length() + LF.length(), // length to remove
						null));

		final var expectedLines = new ArrayList<>(initialLines);
		expectedLines.remove(1);
		assertDocHasLines(expectedLines);

		assertRange(event, 2, 2);
	}

	/**
	 * Tests removing the second and the third line
	 */
	@Test
	void testRemove2Lines() throws Throwable {
		final var initialLines = List.of(
				"//comment1",
				"//comment2",
				"//comment3",
				"//comment4");

		final var event = awaitModelChangedEvent(model, initialLines,
				() -> doc.replace(doc.getLineOffset(1),
						2 * (initialLines.get(1).length() + LF.length()), // length to remove
						null));

		final var expectedLines = new ArrayList<>(initialLines);
		expectedLines.remove(2);
		expectedLines.remove(1);
		assertDocHasLines(expectedLines);

		assertRange(event, 2, 2);
	}

	/**
	 * Tests replacing the second single comment line
	 */
	@Test
	void testReplace1Line() throws Throwable {
		final var initialLines = List.of(
				"//comment1",
				"//comment2",
				"//comment3",
				"//comment4");

		final var event = awaitModelChangedEvent(model, initialLines,
				() -> doc.replace(doc.getLineOffset(1),
						initialLines.get(1).length() + LF.length(), // length to replace
						"//commentX" + LF));

		final var expectedLines = new ArrayList<>(initialLines);
		expectedLines.set(1, "//commentX");
		assertDocHasLines(expectedLines);

		assertRange(event, 2, 2);
	}

	/**
	 * Tests replacing the second single comment line
	 */
	@Test
	void testReplace1LineWithMultilineComment() throws Throwable {
		final var initialLines = List.of(
				"//comment1",
				"//comment2",
				"//comment3",
				"//comment4");

		final var event = awaitModelChangedEvent(model, initialLines,
				() -> doc.replace(doc.getLineOffset(1),
						initialLines.get(1).length() + LF.length(), // length to replace
						"/*commentX" + LF));

		final var expectedLines = new ArrayList<>(initialLines);
		expectedLines.set(1, "/*commentX");
		assertDocHasLines(expectedLines);

		assertRange(event, 2, 3);
	}

	/**
	 * Tests replacing the second and third single comment lines
	 */
	@Test
	void testReplace2LinesWith1Line() throws Throwable {
		final var initialLines = List.of(
				"//comment1",
				"//comment2",
				"//comment3",
				"//comment4",
				"//comment5");

		final var event = awaitModelChangedEvent(model, initialLines,
				() -> doc.replace(doc.getLineOffset(1),
						2 * (initialLines.get(1).length() + LF.length()), // length to replace
						"//commentX" + LF));

		final var expectedLines = new ArrayList<>(initialLines);
		expectedLines.set(1, "//commentX");
		expectedLines.remove(2);
		assertDocHasLines(expectedLines);

		assertRange(event, 2, 2);
	}

	@Test
	void testReplace2LinesPartially() throws Throwable {
		final var initialLines = List.of(
				"//comment1",
				"//comment2",
				"//comment3",
				"//comment4",
				"//comment5");

		final var event = awaitModelChangedEvent(model, initialLines,
				() -> doc.replace(doc.getLineOffset(1) + "//comm".length(),
						("XXX" + LF + "//YYY").length(),
						"XXX" + LF + "//YYY"));

		final var expectedLines = new ArrayList<>(initialLines);
		expectedLines.set(1, "//commXXX");
		expectedLines.set(2, "//YYYmment3");
		assertDocHasLines(expectedLines);

		assertRange(event, 2, 3);
	}

	@Test
	void testSetDocumentContent() throws Throwable {
		final var event1 = awaitModelChangedEvent(model, Collections.emptyList(), () -> doc.set("a"));
		assertRange(event1, 1, doc.getNumberOfLines());
		assertRange(event1, 1, 1);

		final var event2 = awaitModelChangedEvent(model, Collections.emptyList(), () -> doc.set("a\nb"));
		assertRange(event2, 1, doc.getNumberOfLines());
		assertRange(event2, 1, 2);

		final var event3 = awaitModelChangedEvent(model, Collections.emptyList(), () -> doc.set("a\nb\nc"));
		assertRange(event3, 1, doc.getNumberOfLines());
		assertRange(event3, 1, 3);
	}
}
