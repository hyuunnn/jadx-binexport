package dev.jadxbinexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * THE javac scaffolding for the test suite: compiles one source into
 * {@code <tmp>/classes} the same way for every test, so a future compilation
 * tweak (flags, multi-class fixtures) cannot silently apply to some tests and
 * not others - four hand-rolled copies had already started to drift. Each test
 * keeps its own sample fixture; only the plumbing is shared.
 */
final class TestCompiler {

	private TestCompiler() {
	}

	/** Compiles {@code source} as {@code className}, returning the classes dir. */
	static Path compile(Path tmp, String className, String source) throws IOException {
		Path srcDir = Files.createDirectories(tmp.resolve("src"));
		Path classesDir = Files.createDirectories(tmp.resolve("classes"));
		Path src = srcDir.resolve(className + ".java");
		Files.write(src, source.getBytes());
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "JDK (not JRE) required to run this test");
		assertEquals(0, compiler.run(null, null, null, "-d", classesDir.toString(), "-g", src.toString()),
				"javac failed");
		return classesDir;
	}
}
