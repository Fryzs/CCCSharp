<div align="center">

# #️⃣ CC: CSharp

**Пиши програми для CC: Tweaked мовою C#. Перекладач C# → Lua живе в самому моді — .NET не потрібен.**

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-E67E22)
![Requires](https://img.shields.io/badge/requires-CC%3A%20Tweaked-F1C40F)
![Version](https://img.shields.io/badge/version-0.1.0-2E86C1)
![Tests](https://img.shields.io/badge/tests-23%20passing-27AE60)
![License](https://img.shields.io/badge/license-MIT-27AE60)

[English](README.md) · **Українська**

</div>

---

Комп'ютери CC: Tweaked розмовляють Lua. CC: CSharp навчає їх C#. Перекладач написаний на Java і входить у сам мод,
тож працює в одиночній грі й на **будь-якому сервері** — без .NET і сторонніх програм. Твоя програма
перекладається у звичайний Lua і виконується у звичайній пісочниці CC, тому це так само безпечно, як Lua.

## 🚀 Швидкий старт

На будь-якому комп'ютері, черепашці чи кишеньковому комп'ютері:

```
edit hello.cs
csharp hello.cs
```

`csharp` перекладає `hello.cs` у `hello.lua` (поруч) і запускає. `csharp -c hello.cs` — лише перекласти.
`help csharp` — коротка довідка в грі.

Помилки показують рядок у **.cs**:

```
hello.cs:4:19: the name 'foo' does not exist here
hello.cs:7: Unhandled exception. ArgumentOutOfRangeException: Index was out of range...
```

## 💡 Приклади

### Привіт

```csharp
Console.WriteLine("Hello from C#!");
for (int i = 1; i <= 3; i++)
    Console.WriteLine($"{i} x 7 = {i * 7}");
```

### Класи

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

### Реактор IC2 (з [CC: Industrial](https://github.com/Fryzs/CCIndustrial))

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

### Черепашка

```csharp
for (int i = 0; i < 10; i++)
{
    if (Turtle.DetectDown()) Turtle.DigDown();
    Turtle.Forward();
}
```

## 🔌 API CC: Tweaked у стилі C#

Глобальні API CC пишуться з великої літери, як у C#, і перекладаються в Lua-імена:
`Peripheral.Find("monitor")` → `peripheral.find("monitor")`, `Redstone.SetOutput(...)` → `redstone.setOutput(...)`,
`Os.Sleep(1)`, `Term.Clear()`, `Turtle.Forward()`, `Colors.Red`, `Textutils.Serialize(t)`.
Методи периферій так само: `reactor.GetHeat()` → `reactor.getHeat()`.

Значення з Lua мають тип `dynamic`. Поля Lua-таблиць читаються як є: `item.name`, `item.count`.
Lua-таблиці нумеруються з **1** (як у Lua), а списки C# — з **0**.

- `Lua.Multi(Os.PullEvent("key"))` — усі значення, які повернула функція, як `List<dynamic>`.
- `Lua.Global("aura")` — будь-яка глобальна Lua-змінна.

## ✅ Що підтримується

| Що | Підтримується |
| --- | --- |
| **Програма** | top-level statements або клас із `static void Main()` / `Main(string[] args)`; `using` і `namespace` приймаються |
| **Типи** | `int`/`long`/… (цілі), `double`/`float`/`decimal`, `bool`, `string`, `char` (рядок з 1 символу), `var`, `dynamic`/`object`, масиви `T[]`, `List<T>`, `Dictionary<K,V>`, `KeyValuePair`, `Func<>`/`Action<>`, `StringBuilder`, `Random`, `enum` |
| **Класи** | поля, `const`, статичні члени, авто-властивості (`{ get; set; } = ...`), властивості з `get`/`set` і `value`, `=>`-члени, конструктори з `: base(...)` / `: this(...)`, наслідування, `virtual`/`override`/`abstract`, `base.Method()`, `ToString()`, ініціалізатори `new Point { X = 1 }`, перевантаження (за кількістю параметрів), необов'язкові параметри, `params`. `struct` поводиться як клас; інтерфейси приймаються, але не перевіряються |
| **Інструкції** | `if/else`, `switch` (кілька `case`, рядки), `while`, `do`, `for`, `foreach`, `break`, `continue`, `return`, `try/catch/finally` (типізовані `catch`, `throw;`), `throw`, локальні функції |
| **Вирази** | арифметика з цілочисельним `/` як у C#, `%`, порівняння, `&& \|\| !`, бітові операції, `?:`, `??`, `??=`, `?.`, `is T`, `is T x`, `is null`, `is not ...`, приведення `(int)`, `(double)`, `(char)`, лямбди, `$"..."` з форматами (`{x:F2}`, `{n:N0}`, `{v:P1}`, `{i:D3}`, `{b:X}`), `nameof` |
| **Бібліотека** | `Console` (`WriteLine`, `Write`, `ReadLine`, `Clear`), `Math`, `string` (`Join`, `Format`, `IsNullOrEmpty`, …), методи рядків (`Substring`, `Split`, `Replace`, `Trim`, `PadLeft`, …), `Parse`/`TryParse` для `int`/`double`, `Convert`, `char.IsDigit` і т. ін., `Thread.Sleep` |
| **List + LINQ** | `Add`, `Remove`, `Insert`, `Sort`, `Contains`, `IndexOf`, `Where`, `Select`, `OrderBy`, `OrderByDescending`, `First`, `Last`, `Any`, `All`, `Count`, `Sum`, `Min`, `Max`, `Average`, `Take`, `Skip`, `Distinct`, `ToList`, `ForEach`, … |
| **Dictionary** | індексатор, `Add`, `ContainsKey`, `Remove`, `TryGetValue(k, out var v)`, `Keys`, `Values`, `foreach` по парах |
| **Винятки** | `Exception`, `ArgumentException`, `InvalidOperationException`, `FormatException`, `KeyNotFoundException`, `DivideByZeroException`, `ArgumentOutOfRangeException`, … і власні класи винятків. Ctrl+T (Terminate) `catch` не перехоплює |

## 🚧 Поки немає

Власні generic-класи й методи, `async`/`await`, кортежі, `out`/`ref` (крім `TryParse`/`TryGetValue`),
`switch`-вирази, LINQ-запити (`from x in ...`), `HashSet`/`Queue`/`Stack`, копіювання `struct` як значення,
перевантаження з однаковою кількістю параметрів, вкладені типи, `++`/`=` всередині виразу (лише окремою
інструкцією). На все непідтримане перекладач показує зрозумілу помилку з рядком.

Числа — Lua-числа (double): цілі точні до 2⁵³, переповнення `int` не імітується.

## 📦 Встановлення

| | |
| --- | --- |
| Minecraft | 1.21.1 |
| Завантажувач | NeoForge 21.1.x |
| Обов'язково | CC: Tweaked ≥ 1.110 |
| Сторона | **сервер** (і одиночна гра); гравцям на сервері не потрібен |

Поклади `cccsharp-0.1.0.jar` у папку `mods`.

## 🛠️ Збірка й тести

```
./gradlew build
./gradlew test               # 23 тести: C#-програми (і приклади з README) виконуються на Cobalt,
                             # Lua-машині CC: Tweaked
./gradlew runGameTestServer  # без вікна: перевіряє файли в ROM CC і API в справжньому Minecraft
```

## 🧩 Як це влаштовано

`compiler/` — лексер, парсер (AST) і генератор Lua з урахуванням типів. Lua-модуль
`rom/modules/main/cccsharp/runtime.lua` дає списки з нумерацією від 0, словники, класи, винятки й форматування.
Програма `rom/programs/csharp.lua` перекладає, зберігає `.lua`, запускає й переписує позиції помилок у рядки `.cs`.

## 📄 Ліцензія

[MIT](LICENSE). CC: Tweaked — окремий проєкт під своєю ліцензією; жодна його частина сюди не входить.
