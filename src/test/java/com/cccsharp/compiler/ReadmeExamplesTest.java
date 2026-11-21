package com.cccsharp.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Every ```csharp block in README.md must compile; the self-contained ones must run as shown. */
class ReadmeExamplesTest {
    private static List<String> examples() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        Matcher m = Pattern.compile("```csharp\\n(.*?)```", Pattern.DOTALL).matcher(readme);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void allExamplesCompile() throws IOException {
        List<String> examples = examples();
        assertTrue(examples.size() >= 4, "expected the README examples, found " + examples.size());
        for (String cs : examples) {
            CSharpCompiler.compile(cs); // throws CompileException with the position if broken
        }
    }

    @Test
    void helloAndClassesRunAsDescribed() throws IOException {
        List<String> examples = examples();
        assertEquals("Hello from C#!\n1 x 7 = 7\n2 x 7 = 14\n3 x 7 = 21\n", CSharpProgramsTest.run(examples.get(0)));
        assertEquals("circle: 12.57\nrect: 12.00\n", CSharpProgramsTest.run(examples.get(1)));
    }
}
