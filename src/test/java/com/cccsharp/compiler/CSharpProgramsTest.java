package com.cccsharp.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaThread;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.compiler.LoadState;
import org.squiddev.cobalt.function.LuaClosure;
import org.squiddev.cobalt.lib.Bit32Lib;
import org.squiddev.cobalt.lib.CoreLibraries;

/**
 * Compiles real C# programs and runs the Lua on Cobalt, the Lua VM inside CC: Tweaked, then checks
 * what they print. The CC APIs the programs touch (os, peripheral, redstone) are small stubs.
 */
class CSharpProgramsTest {
    private static final String PRELUDE = """
            __out = {}
            function print(...)
              local t = table.pack(...)
              for i = 1, t.n do t[i] = tostring(t[i]) end
              __out[#__out + 1] = table.concat(t, "\\t") .. "\\n"
            end
            function write(s) __out[#__out + 1] = tostring(s) end
            function sleep() end
            os = { clock = function() return 0 end, sleep = function() end,
                   pullEvent = function(filter) return filter or "key", "extra" end }
            __rs = {}
            redstone = { setOutput = function(side, on) __rs[side] = on end }
            peripheral = { find = function(kind)
              return { getHeat = function() return 1234 end, getMaxHeat = function() return 10000 end }
            end }
            local runtime = load(__runtime_src, "@runtime.lua")()
            function require(name)
              if name == "cccsharp.runtime" then return runtime end
              error("module not found: " .. name)
            end
            local program, err = load(__program_src, "@prog.lua")
            if not program then return "LOAD ERROR: " .. err end
            local ok, e = xpcall(program, function(e)
              if type(e) == "table" and e.Message ~= nil then
                return getmetatable(e).__name .. ": " .. tostring(e.Message)
              end
              return tostring(e)
            end)
            if not ok then __out[#__out + 1] = "UNHANDLED: " .. e .. "\\n" end
            return table.concat(__out)
            """;

    private static String runtimeSource() {
        try (InputStream in = CSharpProgramsTest.class.getResourceAsStream(
                "/data/computercraft/lua/rom/modules/main/cccsharp/runtime.lua")) {
            if (in == null) {
                throw new IllegalStateException("runtime.lua not on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compiles and runs a C# program, returning everything it printed. */
    static String run(String csharp) {
        CSharpCompiler.Result result = CSharpCompiler.compile(csharp);
        try {
            LuaState state = new LuaState();
            CoreLibraries.standardGlobals(state);
            Bit32Lib.add(state);
            LuaTable globals = state.globals();
            globals.rawset("__runtime_src", ValueFactory.valueOf(runtimeSource()));
            globals.rawset("__program_src", ValueFactory.valueOf(result.lua()));
            LuaClosure prelude = LoadState.load(state,
                    new ByteArrayInputStream(PRELUDE.getBytes(StandardCharsets.UTF_8)), "=prelude", globals);
            String out = LuaThread.runMain(state, prelude).first().toString();
            if (out.startsWith("LOAD ERROR")) {
                fail(out + "\n--- generated Lua ---\n" + numbered(result.lua()));
            }
            return out;
        } catch (Exception e) {
            throw new AssertionError("running failed: " + e + "\n--- generated Lua ---\n" + numbered(result.lua()), e);
        }
    }

    private static String numbered(String lua) {
        StringBuilder sb = new StringBuilder();
        String[] lines = lua.split("\n");
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%3d  %s%n", i + 1, lines[i]));
        }
        return sb.toString();
    }

    private static void check(String csharp, String expected) {
        String out = run(csharp);
        if (!expected.equals(out)) {
            String lua = CSharpCompiler.compile(csharp).lua();
            assertEquals(expected, out, "--- generated Lua ---\n" + numbered(lua));
        }
    }

    // --- programs --------------------------------------------------------------------------

    @Test
    void basicsAndArithmetic() {
        check("""
                Console.WriteLine("Hello, CC!");
                int a = 7, b = 2;
                Console.WriteLine(a / b);
                Console.WriteLine(a % b);
                Console.WriteLine(7.0 / 2);
                Console.WriteLine(-7 / 2);
                Console.WriteLine($"{a} + {b} = {a + b}");
                double pi = Math.PI;
                Console.WriteLine($"pi ~ {pi:F2}");
                Console.WriteLine("x" + 1 + 2);
                Console.WriteLine(1 + 2 + "x");
                bool t = true;
                Console.WriteLine(t);
                Console.WriteLine(string.Format("{0}-{1}", "a", 5));
                """, """
                Hello, CC!
                3
                1
                3.5
                -3
                7 + 2 = 9
                pi ~ 3.14
                x12
                3x
                True
                a-5
                """);
    }

    @Test
    void loopsAndSwitch() {
        check("""
                for (int i = 0; i < 5; i++) {
                    if (i == 1) continue;
                    if (i == 4) break;
                    Console.Write(i + " ");
                }
                Console.WriteLine();
                int n = 0;
                while (n < 3) n++;
                Console.WriteLine(n);
                do { n--; } while (n > 0);
                Console.WriteLine(n);
                var words = new List<string> { "a", "b", "c" };
                foreach (var w in words) Console.Write(w);
                Console.WriteLine();
                int[] arr = { 3, 1, 2 };
                Console.WriteLine(arr.Length + " " + arr[0]);
                switch (arr[1]) {
                    case 0: Console.WriteLine("zero"); break;
                    case 1:
                    case 2: Console.WriteLine("one or two"); break;
                    default: Console.WriteLine("other"); break;
                }
                string day = "sat";
                switch (day) {
                    case "sat":
                    case "sun":
                        Console.WriteLine("weekend");
                        break;
                    default:
                        Console.WriteLine("weekday");
                        break;
                }
                """, """
                0 2 3\s
                3
                0
                abc
                3 3
                one or two
                weekend
                """);
    }

    @Test
    void classesInheritanceAndOverloads() {
        check("""
                using System;
                using System.Collections.Generic;

                namespace Demo
                {
                    abstract class Shape
                    {
                        public string Name { get; set; }
                        protected Shape(string name) { Name = name; }
                        public abstract double Area();
                        public virtual string Describe() => $"{Name} with area {Area():F1}";
                        public override string ToString() => "Shape(" + Name + ")";
                    }

                    class Circle : Shape
                    {
                        public double R;
                        public Circle(double r) : base("circle") { R = r; }
                        public override double Area() => Math.PI * R * R;
                    }

                    class Rect : Shape
                    {
                        public int W { get; }
                        public int H { get; }
                        public Rect(int w, int h) : base("rect") { W = w; H = h; }
                        public override double Area() => W * H;
                        public override string Describe() => base.Describe() + "!";
                    }

                    class Counter
                    {
                        public static int Total;
                        private int count;
                        public int Count => count;
                        public void Add() { count++; Total++; }
                        public void Add(int n) { count += n; Total += n; }
                    }

                    class Program
                    {
                        static void Main(string[] args)
                        {
                            var shapes = new List<Shape> { new Circle(1), new Rect(2, 3) };
                            foreach (var s in shapes) Console.WriteLine(s.Describe());
                            Console.WriteLine(shapes[1]);
                            Console.WriteLine(shapes[0] is Circle);
                            var c = new Counter();
                            c.Add(); c.Add(5);
                            var d = new Counter();
                            d.Add();
                            Console.WriteLine($"{c.Count} {d.Count} {Counter.Total}");
                            Console.WriteLine(Square(4));
                        }

                        static int Square(int x) => x * x;
                    }
                }
                """, """
                circle with area 3.1
                rect with area 6.0!
                Shape(rect)
                True
                6 1 7
                16
                """);
    }

    @Test
    void collectionsAndLinq() {
        check("""
                using System.Text;
                var nums = new List<int> { 5, 3, 8, 1 };
                nums.Add(4);
                nums.Sort();
                Console.WriteLine(string.Join(",", nums));
                var evens = nums.Where(x => x % 2 == 0).ToList();
                Console.WriteLine(string.Join(",", evens));
                Console.WriteLine(nums.Sum() + " " + nums.Max() + " " + nums.Count);
                Console.WriteLine(nums.Select(x => x * 10).First());
                nums.RemoveAt(0);
                Console.WriteLine(nums.Contains(1) + " " + nums.IndexOf(8));
                var ages = new Dictionary<string, int> { { "ann", 30 }, { "bob", 25 } };
                ages["cid"] = 40;
                ages["ann"] += 1;
                foreach (var kv in ages) Console.WriteLine($"{kv.Key}={kv.Value}");
                if (ages.TryGetValue("bob", out var bobAge)) Console.WriteLine("bob is " + bobAge);
                Console.WriteLine(ages.ContainsKey("zed"));
                var sorted = ages.Keys.OrderByDescending(k => ages[k]).ToList();
                Console.WriteLine(string.Join(" ", sorted));
                var sb = new StringBuilder();
                sb.Append("a").Append(1).AppendLine("!");
                Console.Write(sb.ToString());
                string csv = "x, y,,z";
                var parts = csv.Split(',', StringSplitOptions.RemoveEmptyEntries);
                Console.WriteLine(parts.Length + ":" + parts[1].Trim().ToUpper());
                """, """
                1,3,4,5,8
                4,8
                21 8 5
                10
                False 3
                ann=31
                bob=25
                cid=40
                bob is 25
                False
                cid ann bob
                a1!
                3:Y
                """);
    }

    @Test
    void exceptions() {
        check("""
                static int Divide(int a, int b)
                {
                    try
                    {
                        return a / b;
                    }
                    catch (DivideByZeroException e)
                    {
                        Console.WriteLine("caught: " + e.Message);
                        return -1;
                    }
                    finally
                    {
                        Console.WriteLine("finally");
                    }
                }

                Console.WriteLine(Divide(6, 3));
                Console.WriteLine(Divide(1, 0));
                try
                {
                    throw new MyError("boom");
                }
                catch (Exception e)
                {
                    Console.WriteLine(e.Message);
                }
                try { int.Parse("abc"); } catch (FormatException) { Console.WriteLine("bad number"); }
                for (int i = 0; i < 3; i++)
                {
                    try { if (i == 1) continue; Console.WriteLine("i=" + i); } finally { }
                }
                throw new InvalidOperationException("nope");

                class MyError : Exception
                {
                    public MyError(string m) : base("my: " + m) { }
                }
                """, """
                finally
                2
                caught: Attempted to divide by zero.
                finally
                -1
                my: boom
                bad number
                i=0
                i=2
                UNHANDLED: InvalidOperationException: nope
                """);
    }

    @Test
    void enumsLambdasLocalFunctions() {
        check("""
                Dir d = Dir.South;
                Console.WriteLine((int)d + (int)Dir.West);
                Func<int, int> twice = x => x * 2;
                Console.WriteLine(twice(21));
                int counter = 0;
                Action inc = () => { counter++; };
                inc(); inc();
                Console.WriteLine(counter);
                string? name = null;
                Console.WriteLine(name ?? "nobody");
                int score = 75;
                Console.WriteLine(score >= 50 ? "pass" : "fail");
                Console.WriteLine(Fib(10));
                var rnd = new Random(42);
                int r = rnd.Next(1, 7);
                Console.WriteLine(r >= 1 && r <= 6);
                Console.WriteLine(Math.Max(3, 9) + Math.Abs(-2));
                Console.WriteLine(Math.Round(2.5) + " " + Math.Round(3.5) + " " + Math.Round(2.567, 2));

                int Fib(int n) => n < 2 ? n : Fib(n - 1) + Fib(n - 2);

                enum Dir { North, East, South = 5, West }
                """, """
                11
                42
                2
                nobody
                pass
                55
                True
                11
                2 4 2.57
                """);
    }

    @Test
    void computerCraftInterop() {
        check("""
                var reactor = Peripheral.Find("ic2_reactor");
                double fill = reactor.GetHeat() / (double)reactor.GetMaxHeat();
                Console.WriteLine($"{fill:P1}");
                Redstone.SetOutput("back", fill > 0.5);
                Os.Sleep(0.1);
                var ev = Lua.Multi(Os.PullEvent("redstone"));
                Console.WriteLine(ev[0] + " " + ev.Count);
                """, """
                12.3 %
                redstone 2
                """);
    }

    // --- errors ----------------------------------------------------------------------------

    private static CompileException compileError(String csharp) {
        return assertThrows(CompileException.class, () -> CSharpCompiler.compile(csharp));
    }

    @Test
    void reportsUnknownNamesWithPosition() {
        CompileException e = compileError("int x = 1;\nConsole.WriteLine(foo);\n");
        assertEquals(2, e.line());
        assertEquals(19, e.column());
        assertTrue(e.getMessage().contains("'foo' does not exist"), e.getMessage());
    }

    @Test
    void reportsTypeErrors() {
        CompileException e = compileError("int x = \"a\" / 2;");
        assertTrue(e.getMessage().contains("needs numbers"), e.getMessage());
    }

    @Test
    void reportsUnsupportedFeatures() {
        CompileException e = compileError("goto end;");
        assertTrue(e.getMessage().contains("not supported"), e.getMessage());
    }

    @Test
    void reportsMissingSemicolon() {
        CompileException e = compileError("int x = 1");
        assertTrue(e.getMessage().contains("expected ';'"), e.getMessage());
    }
}
