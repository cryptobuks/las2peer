package i5.las2peer.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ServiceAgentGeneratorTest {

	private final ByteArrayOutputStream standardOut = new ByteArrayOutputStream();
	private final ByteArrayOutputStream standardError = new ByteArrayOutputStream();

	@Before
	public void detourSystemStreams() {
		System.setOut(new PrintStream(standardOut));
		System.setErr(new PrintStream(standardError));
	}

	@After
	public void clearSystemStreams() {
		System.setOut(null);
		System.setErr(null);
	}

	@Test
	public void testMainUsage() {
		ServiceAgentGenerator.main(new String[0]);
		assertTrue(("" + standardError.toString()).contains("usage:"));
		assertEquals("", standardOut.toString());
	}

	@Test
	public void testMainNormal() {
		String className = "a.test.package.WithAService@1.0";
		ServiceAgentGenerator.main(new String[] { className, "mypass" });

		assertEquals("", standardError.toString());

		String output = standardOut.toString();

		assertTrue(output.contains("serviceclass=\"" + className + "\""));
	}
}
