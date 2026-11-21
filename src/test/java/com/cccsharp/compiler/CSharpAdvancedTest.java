package com.cccsharp.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Trickier programs: control flow through try, switch inside loops, patterns, initializers, nesting. */
class CSharpAdvancedTest {
    private static void check(String csharp, String expected) {
        assertEquals(expected, CSharpProgramsTest.run(csharp), () -> {
            String lua = CSharpCompiler.compile(csharp).lua();
            StringBuilder sb = new StringBuilder("--- generated Lua ---\n");
            String[] lines = lua.split("\n");
            for (int i = 0; i < lines.length; i++) {
                sb.append(String.format("%3d  %s%n", i + 1, lines[i]));
            }
            return sb.toString();
        });
    }

    @Test
    void controlFlowThroughTryAndSwitch() {
        check("""
                static int FirstNegative(List<int> xs)
                {
                    foreach (var x in xs)
                    {
                        try
                        {
                            if (x < 0) return x;
                            if (x == 99) break;
                        }
                        finally
                        {
                            Console.Write(".");
                        }
                    }
                    return 0;
                }

                Console.WriteLine(FirstNegative(new List<int> { 1, 2, -3, 4 }));
                Console.WriteLine(FirstNegative(new List<int> { 1, 99, -3 }));

                for (int i = 0; i < 6; i++)
                {
                    switch (i % 3)
                    {
                        case 0:
                            if (i > 2) { Console.Write("Z"); break; }
                            Console.Write("z");
                            break;
                        case 1:
                            continue;
                        default:
                            Console.Write("d");
                            break;
                    }
                    Console.Write(i);
                }
                Console.WriteLine();

                int k = 0;
                while (true)
                {
                    k++;
                    if (k % 2 == 0) continue;
                    if (k > 7) break;
                    Console.Write(k);
                }
                Console.WriteLine();
                int m = 0;
                do
                {
                    m++;
                    if (m == 2) continue;
                    Console.Write(m);
                } while (m < 4);
                Console.WriteLine();
                """, """
                ...-3
                ..0
                z0d2Z3d5
                1357
                134
                """);
    }

    @Test
    void propertiesInitializersPatterns() {
        check("""
                var t = new Thermo { Name = "core" };
                t.Celsius = 25;
                Console.WriteLine($"{t.Name}: {t.Fahrenheit:F1}F");
                t.Celsius = -500;
                Console.WriteLine(t.Celsius);

                object shape = new Circle(2);
                if (shape is Circle c && c.R > 1) Console.WriteLine("big circle " + c.R);
                if (shape is not Square) Console.WriteLine("not a square");

                Thermo? none = null;
                Console.WriteLine(none?.Name ?? "no thermo");

                Console.WriteLine(Sum(1, 2, 3) + " " + Sum());
                Console.WriteLine(Greet("Ann") + " / " + Greet("Bob", "Hi"));

                int end = 5;
                int local = end * 2;
                Console.WriteLine(local);

                static int Sum(params int[] xs)
                {
                    int s = 0;
                    foreach (var x in xs) s += x;
                    return s;
                }

                static string Greet(string who, string word = "Hello") => $"{word}, {who}";

                class Thermo
                {
                    private double celsius;
                    public string Name { get; set; } = "unnamed";
                    public double Celsius
                    {
                        get { return celsius; }
                        set
                        {
                            if (value < -273.15) value = -273.15;
                            celsius = value;
                        }
                    }
                    public double Fahrenheit => celsius * 9 / 5 + 32;
                }

                class Circle { public double R; public Circle(double r) { R = r; } }
                class Square { }
                """, """
                core: 77.0F
                -273.15
                big circle 2
                not a square
                no thermo
                6 0
                Hello, Ann / Hi, Bob
                10
                """);
    }

    @Test
    void stringsAndParsing() {
        check("""
                string s = "Hello, World";
                Console.WriteLine(s.Length + " " + s[0] + s[s.Length - 1]);
                Console.WriteLine(s.Substring(7) + "|" + s.Substring(0, 5));
                Console.WriteLine(s.IndexOf("World") + " " + s.Contains("lo,") + " " + s.StartsWith("He"));
                Console.WriteLine(s.Replace("l", "L").ToLower());
                Console.WriteLine("[" + "7".PadLeft(3, '0') + "][" + "ab".PadRight(4) + "]");
                string acc = "";
                foreach (var ch in "abc") acc += ch.ToUpper();
                Console.WriteLine(acc);
                if (int.TryParse("42", out int v)) Console.WriteLine(v + 1);
                if (!int.TryParse("x1", out var bad)) Console.WriteLine("bad=" + bad);
                Console.WriteLine(double.Parse("2.5") * 2);
                Console.WriteLine(string.IsNullOrEmpty("") + " " + string.Join("-", new[] { 1, 2, 3 }));
                Console.WriteLine($"{1234567.891:N2} {255:X} {7:D3} {0.5:P0}");
                Console.WriteLine(char.IsDigit('7') + " " + (char)65 + " " + (int)'a');
                """, """
                12 Hd
                World|Hello
                7 True True
                hello, world
                [007][ab  ]
                ABC
                43
                bad=0
                5
                True 1-2-3
                1,234,567.89 FF 007 50 %
                True A 97
                """);
    }

    @Test
    void nestedCollectionsAndStatics() {
        check("""
                var grid = new List<List<int>>();
                for (int r = 0; r < 3; r++)
                {
                    var row = new List<int>();
                    for (int c = 0; c < 3; c++) row.Add(r * 3 + c);
                    grid.Add(row);
                }
                Console.WriteLine(grid[2][1] + " " + grid.Count + " " + grid[0].Count);
                var groups = new Dictionary<string, List<string>>();
                foreach (var w in new[] { "apple", "avocado", "banana", "blueberry", "cherry" })
                {
                    string key = w.Substring(0, 1);
                    if (!groups.ContainsKey(key)) groups[key] = new List<string>();
                    groups[key].Add(w);
                }
                foreach (var g in groups) Console.WriteLine(g.Key + ": " + string.Join(",", g.Value));
                Console.WriteLine(Config.Describe());
                Config.Level = 3;
                Console.WriteLine(Config.Describe());
                var matrix = new int[3];
                matrix[1] = 7;
                Console.WriteLine(string.Join(" ", matrix));

                static class Config
                {
                    public const int Max = 10;
                    public static int Level = 1;
                    public static string Describe() => $"level {Level} of {Max}";
                }
                """, """
                7 3 3
                a: apple,avocado
                b: banana,blueberry
                c: cherry
                level 1 of 10
                level 3 of 10
                0 7 0
                """);
    }

    @Test
    void ccStyleApisAndUncaughtErrors() {
        check("""
                var list = new List<int> { 1, 2 };
                try { Console.WriteLine(list[5]); }
                catch (ArgumentOutOfRangeException) { Console.WriteLine("out of range"); }
                var d = new Dictionary<string, int>();
                try { Console.WriteLine(d["missing"]); }
                catch (KeyNotFoundException e) { Console.WriteLine(e.Message); }
                Shape s = null;
                Console.WriteLine(s == null);
                class Shape { }
                """, """
                out of range
                The given key 'missing' was not present in the dictionary.
                True
                """);
    }
}
