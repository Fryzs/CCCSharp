<div align="center">

# #️⃣ CC: CSharp

**Write CC: Tweaked programs in C#. A C# → Lua compiler that lives inside the mod — no .NET needed.**

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-E67E22)
![Requires](https://img.shields.io/badge/requires-CC%3A%20Tweaked-F1C40F)
![Version](https://img.shields.io/badge/version-0.1.0-2E86C1)
![Tests](https://img.shields.io/badge/tests-23%20passing-27AE60)
![License](https://img.shields.io/badge/license-MIT-27AE60)

**English** · [Українська](README.uk.md)

</div>

---

CC: Tweaked computers speak Lua. CC: CSharp teaches them C#. The compiler is written in Java and ships inside the
mod, so it works in single player and on **any server** — no .NET, no external tools. Your program is translated
to plain Lua and runs in CC's normal sandbox, so it is exactly as safe as Lua.

## 🚀 Quick start

On any computer, turtle or pocket computer:

```
edit hello.cs
csharp hello.cs
```

`csharp` translates `hello.cs` into `hello.lua` (saved next to it) and runs it. `csharp -c hello.cs` only translates.
`help csharp` shows a short in-game guide.

Errors point at your **.cs** line:

```
hello.cs:4:19: the name 'foo' does not exist here
hello.cs:7: Unhandled exception. ArgumentOutOfRangeException: Index was out of range...
```

## 💡 Examples

### Hello

```csharp
Console.WriteLine("Hello from C#!");
for (int i = 1; i <= 3; i++)
    Console.WriteLine($"{i} x 7 = {i * 7}");
```

### Classes

```csharp
var shapes = new List<Shape> { new Circle(2), new Rect(3, 4) };
foreach (var s in shapes)
    Console.WriteLine($"{s.Name}: {s.Area():F2}");

abstract class Shape
{
    public string Name { get; }
    protected Shape(string name) { Name = name; }
    public abstract double Area();
}

class Circle : Shape
{
    double r;
    public Circle(double r) : base("circle") { this.r = r; }
    public override double Area() => Math.PI * r * r;
}

class Rect : Shape
{
    int w, h;
    public Rect(int w, int h) : base("rect") { this.w = w; this.h = h; }
    public override double Area() => w * h;
}
```

### IC2 reactor (with [CC: Industrial](https://github.com/Fryzs/CCIndustrial))

```csharp
var reactor = Peripheral.Find("ic2_reactor");
Redstone.SetOutput("back", true);
while (true)
{
    double fill = reactor.GetHeatFill();
    Console.WriteLine($"Heat {fill:P0}, {reactor.GetEUOutput()} EU/t");
    if (fill > 0.5)
    {
        Redstone.SetOutput("back", false);
        Console.WriteLine("SCRAM!");
        break;
    }
    Os.Sleep(1);
}
```

### Turtle

```csharp
for (int i = 0; i < 10; i++)
{
    if (Turtle.DetectDown()) Turtle.DigDown();
    Turtle.Forward();
}
```

## 🔌 CC: Tweaked APIs, C# style

CC's global APIs are written PascalCase, like C#, and turned into their Lua names:
`Peripheral.Find("monitor")` → `peripheral.find("monitor")`, `Redstone.SetOutput(...)` → `redstone.setOutput(...)`,
`Os.Sleep(1)`, `Term.Clear()`, `Turtle.Forward()`, `Colors.Red`, `Textutils.Serialize(t)`.
Peripheral methods work the same way: `reactor.GetHeat()` → `reactor.getHeat()`.

Values coming from Lua are `dynamic`. Fields of Lua tables are read as written: `item.name`, `item.count`.
Lua tables count from **1** (as in Lua); C# lists count from **0**.

- `Lua.Multi(Os.PullEvent("key"))` — every value a function returned, as a `List<dynamic>`.
- `Lua.Global("aura")` — any global Lua variable.

## ✅ What is supported

| Area | Supported |
| --- | --- |
| **Program** | top-level statements, or a class with `static void Main()` / `Main(string[] args)`; `using` and `namespace` are accepted |
| **Types** | `int`/`long`/… (whole numbers), `double`/`float`/`decimal`, `bool`, `string`, `char` (a 1-character string), `var`, `dynamic`/`object`, arrays `T[]`, `List<T>`, `Dictionary<K,V>`, `KeyValuePair`, `Func<>`/`Action<>`, `StringBuilder`, `Random`, `enum` |
| **Classes** | fields, `const`, static members, auto-properties (`{ get; set; } = ...`), properties with `get`/`set` and `value`, `=>` members, constructors with `: base(...)` / `: this(...)`, inheritance, `virtual`/`override`/`abstract`, `base.Method()`, `ToString()`, object initializers `new Point { X = 1 }`, overloads (by parameter count), optional parameters, `params`. `struct` behaves like a class; interfaces are accepted but not checked |
| **Statements** | `if/else`, `switch` (several `case`s, strings), `while`, `do`, `for`, `foreach`, `break`, `continue`, `return`, `try/catch/finally` (typed `catch`, `throw;`), `throw`, local functions |
| **Expressions** | arithmetic with C#'s integer `/`, `%`, comparisons, `&& \|\| !`, bit operators, `?:`, `??`, `??=`, `?.`, `is T`, `is T x`, `is null`, `is not ...`, casts `(int)`, `(double)`, `(char)`, lambdas, `$"..."` with formats (`{x:F2}`, `{n:N0}`, `{v:P1}`, `{i:D3}`, `{b:X}`), `nameof` |
| **Library** | `Console` (`WriteLine`, `Write`, `ReadLine`, `Clear`), `Math`, `string` (`Join`, `Format`, `IsNullOrEmpty`, …), string methods (`Substring`, `Split`, `Replace`, `Trim`, `PadLeft`, …), `int`/`double` `Parse`/`TryParse`, `Convert`, `char.IsDigit` and friends, `Thread.Sleep` |
| **List + LINQ** | `Add`, `Remove`, `Insert`, `Sort`, `Contains`, `IndexOf`, `Where`, `Select`, `OrderBy`, `OrderByDescending`, `First`, `Last`, `Any`, `All`, `Count`, `Sum`, `Min`, `Max`, `Average`, `Take`, `Skip`, `Distinct`, `ToList`, `ForEach`, … |
| **Dictionary** | indexer, `Add`, `ContainsKey`, `Remove`, `TryGetValue(k, out var v)`, `Keys`, `Values`, `foreach` over pairs |
| **Exceptions** | `Exception`, `ArgumentException`, `InvalidOperationException`, `FormatException`, `KeyNotFoundException`, `DivideByZeroException`, `ArgumentOutOfRangeException`, … and your own exception classes. Ctrl+T (Terminate) is never swallowed by `catch` |

## 🚧 Not yet

Your own generic classes and methods, `async`/`await`, tuples, `out`/`ref` (beyond `TryParse`/`TryGetValue`),
`switch` expressions, LINQ query syntax (`from x in ...`), `HashSet`/`Queue`/`Stack`, `struct` value copying,
overloads with the same parameter count, nested types, `++`/`=` inside an expression (use them as their own
statement). Anything unsupported is reported as a clear error with its line.

Numbers are Lua numbers (doubles): whole numbers are exact up to 2⁵³, and `int` overflow is not emulated.

## 📦 Installation

| | |
| --- | --- |
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.x |
| Required | CC: Tweaked ≥ 1.110 |
| Side | **server** (and single player); clients joining a server do not need it |

Drop `cccsharp-0.1.0.jar` into the `mods` folder.

## 🛠️ Building and tests

```
./gradlew build
./gradlew test               # 23 tests: C# programs (including this README's examples) run on Cobalt,
                             # the Lua VM inside CC: Tweaked
./gradlew runGameTestServer  # headless: checks the ROM files and the API inside real Minecraft
```

## 🧩 How it works

`compiler/` holds the lexer, the parser (an AST) and a type-aware Lua generator. The Lua module
`rom/modules/main/cccsharp/runtime.lua` provides 0-based lists, dictionaries, classes, exceptions and formatting.
The `rom/programs/csharp.lua` program translates, saves the `.lua`, runs it, and rewrites error positions into
`.cs` lines.

## 📄 License

[MIT](LICENSE). CC: Tweaked is a separate project under its own license; no part of it is included here.
