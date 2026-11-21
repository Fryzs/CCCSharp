--- Runtime for programs compiled by CC: CSharp (C# -> Lua).
-- Provides 0-based List<T>, Dictionary<K, V>, classes, C# exceptions and string helpers.
-- Generated code does `local __rt = require("cccsharp.runtime")`.

local rt = {}

local floor, ceil, abs, huge = math.floor, math.ceil, math.abs, math.huge
local unpack = table.unpack or unpack

---------------------------------------------------------------------------------------------
-- classes
---------------------------------------------------------------------------------------------

function rt.class(name, base)
  local c = {}
  c.__index = c
  c.__name = name
  c.__base = base
  c.__tostring = function(o) return rt.str(o) end
  if base then setmetatable(c, { __index = base }) end
  return c
end

function rt.new(cls, ctor, ...)
  local o = setmetatable({}, cls)
  local finit = cls.__finit
  if finit then finit(o) end
  local f = cls[ctor]
  if f then f(o, ...) end
  return o
end

--- Does object o belong to class cls (or a class derived from it)?
function rt.is(o, cls)
  if type(o) ~= "table" then return false end
  local c = getmetatable(o)
  while c do
    if c == cls then return true end
    c = rawget(c, "__base")
  end
  return false
end

function rt.cast(o, cls)
  if o ~= nil and not rt.is(o, cls) then
    rt.throw(rt.new(rt.ex.InvalidCastException, "__ctor",
      "Unable to cast " .. rt.typeName(o) .. " to " .. cls.__name .. "."))
  end
  return o
end

function rt.typeName(o)
  local t = type(o)
  if t == "table" then
    local mt = getmetatable(o)
    if mt and mt.__name then return mt.__name end
    return "table"
  end
  if t == "number" then return rt.isInt(o) and "Int32" or "Double" end
  if t == "string" then return "String" end
  if t == "boolean" then return "Boolean" end
  return t
end

function rt.defaultToString(o)
  local mt = getmetatable(o)
  return mt and mt.__name or "Object"
end

---------------------------------------------------------------------------------------------
-- exceptions
---------------------------------------------------------------------------------------------

local Exception = rt.class("Exception", nil)
function Exception.__finit(self) self.Message = "" end
function Exception.__ctor(self, message)
  if message == nil then
    message = "Exception of type '" .. getmetatable(self).__name .. "' was thrown."
  end
  self.Message = message
end
function Exception.ToString(self)
  return getmetatable(self).__name .. ": " .. rt.str(self.Message)
end

rt.ex = { Exception = Exception }
for _, n in ipairs({
  "SystemException", "ArgumentException", "ArgumentNullException", "ArgumentOutOfRangeException",
  "InvalidOperationException", "FormatException", "KeyNotFoundException", "IndexOutOfRangeException",
  "DivideByZeroException", "NotImplementedException", "NotSupportedException", "NullReferenceException",
  "OverflowException", "TimeoutException", "InvalidCastException", "ApplicationException",
}) do
  rt.ex[n] = rt.class(n, Exception)
end

local function newEx(kind, message)
  return rt.new(rt.ex[kind], "__ctor", message)
end
rt.newEx = newEx

--- Turns anything that was thrown (a Lua error string, a CC error, an exception) into an exception.
local function asException(err)
  if rt.is(err, Exception) then return err end
  local text = type(err) == "string" and err or tostring(err)
  local where, message = text:match("^([^:%s]+:%d+): (.*)$")
  local ex = newEx("Exception", message or text)
  ex.__where = where
  return ex
end
rt.asException = asException

--- throw: remembers where it was thrown from so the program runner can show the C# line.
function rt.throw(e)
  if e == nil then
    e = newEx("NullReferenceException", "Object reference not set to an instance of an object.")
  end
  e = asException(e)
  if e.__where == nil then
    -- walk up the stack to the first frame in the user's program (not this runtime)
    for level = 3, 20 do
      local _, w = pcall(error, "", level)
      if type(w) ~= "string" or w == "" then break end
      if not w:find("runtime%.lua:") then
        e.__where = w:match("^(.-:%d+):")
        break
      end
    end
  end
  error(e, 0)
end

--- try/catch/finally. The body and handler return nothing normally, or a status:
--- "ret", value (return from the method), "brk" (break), "cnt" (continue).
function rt.try(body, handler, finally)
  local res = table.pack(pcall(body))
  if not res[1] then
    local err = res[2]
    if err == "Terminated" then -- Ctrl+T: never swallowed by catch
      if finally then finally() end
      error(err, 0)
    end
    if handler then
      res = table.pack(pcall(handler, asException(err)))
      if not res[1] then
        if finally then finally() end
        error(res[2], 0)
      end
    else
      if finally then finally() end
      error(err, 0)
    end
  end
  if finally then finally() end
  return unpack(res, 2, res.n)
end

---------------------------------------------------------------------------------------------
-- numbers
---------------------------------------------------------------------------------------------

function rt.isInt(x)
  return type(x) == "number" and x == floor(x) and abs(x) ~= huge
end

function rt.idiv(a, b)
  if b == 0 then rt.throw(newEx("DivideByZeroException", "Attempted to divide by zero.")) end
  local q = a / b
  if q >= 0 then return floor(q) else return ceil(q) end
end

function rt.toint(x)
  if x ~= x then return 0 end
  if x >= 0 then return floor(x) else return ceil(x) end
end

function rt.round(x, digits)
  local m = 10 ^ (digits or 0)
  local v = x * m
  local f = floor(v)
  local diff = v - f
  local r
  if diff > 0.5 then r = f + 1
  elseif diff < 0.5 then r = f
  else r = (f % 2 == 0) and f or f + 1 end -- banker's rounding, like C#
  return r / m
end

function rt.sign(x)
  if x > 0 then return 1 elseif x < 0 then return -1 else return 0 end
end

function rt.add(a, b)
  if type(a) == "number" and type(b) == "number" then return a + b end
  return rt.str(a) .. rt.str(b)
end

function rt.ticks()
  return floor(os.clock() * 1000)
end

function rt.compare(a, b)
  if a == b then return 0 end
  if a == nil then return -1 end
  if b == nil then return 1 end
  if a < b then return -1 end
  return 1
end

function rt.equals(a, b)
  if type(a) == "table" then
    local mt = getmetatable(a)
    if mt and mt.Equals and mt ~= rt.List and mt ~= rt.Dict then return mt.Equals(a, b) end
  end
  return a == b
end

local function numberString(v)
  if v ~= v then return "NaN" end
  if v == huge then return "∞" end
  if v == -huge then return "-∞" end
  if v == floor(v) and abs(v) < 1e15 then return string.format("%d", v) end
  local s = string.format("%.15g", v)
  if tonumber(s) ~= v then s = string.format("%.17g", v) end
  return s
end

--- What C# would print for a value.
function rt.str(v)
  local t = type(v)
  if v == nil then return "" end
  if t == "string" then return v end
  if t == "boolean" then return v and "True" or "False" end
  if t == "number" then return numberString(v) end
  if t == "table" then
    local mt = getmetatable(v)
    if mt then
      local ts = mt.ToString
      if ts then return ts(v) end
      if mt.__name then return mt.__name end
    end
  end
  return tostring(v)
end

--- Number formats: F2, N0, D3, P1, X, E2, and custom ones like 0.00 or #,##0.
function rt.fmt(v, spec)
  if spec == nil or spec == "" then return rt.str(v) end
  if type(v) ~= "number" then return rt.str(v) end
  local kind, digits = spec:match("^([FfNnDdPpXxEeCcGg])(%d*)$")
  digits = tonumber(digits)
  if kind then
    local k = kind:upper()
    if k == "F" then return string.format("%." .. (digits or 2) .. "f", v) end
    if k == "D" then
      local s = string.format("%d", rt.toint(v))
      if digits then
        local neg = s:sub(1, 1) == "-"
        if neg then s = s:sub(2) end
        s = string.rep("0", digits - #s) .. s
        if neg then s = "-" .. s end
      end
      return s
    end
    if k == "N" or k == "C" then
      local s = string.format("%." .. (digits or 2) .. "f", abs(v))
      local int, frac = s:match("^(%d+)(.*)$")
      int = int:reverse():gsub("(%d%d%d)", "%1,"):reverse():gsub("^,", "")
      return (v < 0 and "-" or "") .. (k == "C" and "$" or "") .. int .. frac
    end
    if k == "P" then return string.format("%." .. (digits or 2) .. "f", v * 100) .. " %" end
    if k == "X" then
      local s = string.format(kind == "X" and "%X" or "%x", rt.toint(v))
      if digits then s = string.rep("0", digits - #s) .. s end
      return s
    end
    if k == "E" then return string.format("%." .. (digits or 6) .. (kind == "E" and "E" or "e"), v) end
    if k == "G" then return rt.str(v) end
  end
  -- custom: count decimals after the point, use thousands separators when ',' appears
  local decimals = 0
  local point = spec:find("%.")
  if point then decimals = #spec:sub(point + 1):gsub("[^0#]", "") end
  local s = string.format("%." .. decimals .. "f", abs(v))
  if spec:find(",") then
    local int, frac = s:match("^(%d+)(.*)$")
    s = int:reverse():gsub("(%d%d%d)", "%1,"):reverse():gsub("^,", "") .. frac
  end
  return (v < 0 and "-" or "") .. s
end

--- Composite formatting: string.Format("{0} is {1:F2}", a, b).
function rt.format(fmt, ...)
  local args = table.pack(...)
  local out = fmt:gsub("{{", "\1"):gsub("}}", "\2"):gsub("{(%d+)(,?%-?%d*):?([^}]*)}", function(i, align, spec)
    local v = args[tonumber(i) + 1]
    local s = spec ~= "" and rt.fmt(v, spec) or rt.str(v)
    local width = tonumber(align:sub(2))
    if width then
      if width > 0 then s = string.rep(" ", width - #s) .. s
      else s = s .. string.rep(" ", -width - #s) end
    end
    return s
  end)
  return (out:gsub("\1", "{"):gsub("\2", "}"))
end

function rt.parseInt(s)
  local ok, v = rt.tryParseInt(s)
  if not ok then rt.throw(newEx("FormatException", "The input string '" .. rt.str(s) .. "' was not in a correct format.")) end
  return v
end

function rt.tryParseInt(s)
  if type(s) ~= "string" then return false, 0 end
  local t = s:match("^%s*([+-]?%d+)%s*$")
  if not t then return false, 0 end
  return true, tonumber(t)
end

function rt.parseFloat(s)
  local ok, v = rt.tryParseFloat(s)
  if not ok then rt.throw(newEx("FormatException", "The input string '" .. rt.str(s) .. "' was not in a correct format.")) end
  return v
end

function rt.tryParseFloat(s)
  if type(s) ~= "string" then return false, 0 end
  local t = s:match("^%s*(.-)%s*$")
  if t:find("^0[xX]") then return false, 0 end
  local v = tonumber(t)
  if v == nil then return false, 0 end
  return true, v
end

function rt.parseBool(s)
  local ok, v = rt.tryParseBool(s)
  if not ok then rt.throw(newEx("FormatException", "String '" .. rt.str(s) .. "' was not recognized as a valid Boolean.")) end
  return v
end

function rt.tryParseBool(s)
  if type(s) ~= "string" then return false, false end
  local t = s:match("^%s*(.-)%s*$"):lower()
  if t == "true" then return true, true end
  if t == "false" then return true, false end
  return false, false
end

function rt.convertInt(x)
  if type(x) == "string" then return rt.parseInt(x) end
  if type(x) == "boolean" then return x and 1 or 0 end
  return rt.round(x)
end

function rt.convertFloat(x)
  if type(x) == "string" then return rt.parseFloat(x) end
  if type(x) == "boolean" then return x and 1 or 0 end
  return x
end

function rt.convertBool(x)
  if type(x) == "string" then return rt.parseBool(x) end
  if type(x) == "number" then return x ~= 0 end
  return x and true or false
end

---------------------------------------------------------------------------------------------
-- console
---------------------------------------------------------------------------------------------

function rt.clear()
  term.clear()
  term.setCursorPos(1, 1)
end

function rt.readKey()
  local _, ch = os.pullEvent("char")
  return { KeyChar = ch }
end

---------------------------------------------------------------------------------------------
-- List<T> (also arrays): elements live at [1..n], C# indexes them from 0
---------------------------------------------------------------------------------------------

local List = rt.class("List", nil)
rt.List = List

local function outOfRange(i, n)
  rt.throw(newEx("ArgumentOutOfRangeException",
    "Index was out of range. Must be non-negative and less than the size of the collection. (index " ..
    rt.str(i) .. ", count " .. n .. ")"))
end

function rt.list(packed)
  local l = setmetatable({ n = 0 }, List)
  if packed then
    for i = 1, packed.n or #packed do l[i] = packed[i] end
    l.n = packed.n or #packed
  end
  return l
end

function rt.listOf(...)
  return rt.list(table.pack(...))
end

function rt.array(size, default)
  local l = setmetatable({ n = size }, List)
  for i = 1, size do l[i] = default end
  return l
end

--- new List<T>(collection): copies a List, a Dictionary's pairs, or a Lua array.
function rt.listFrom(src)
  local l = rt.list()
  for _, v in rt.each(src) do l:Add(v) end
  return l
end

function rt.isList(x) return getmetatable(x) == List end

function List:get(i)
  if type(i) ~= "number" or i < 0 or i >= self.n or i ~= floor(i) then outOfRange(i, self.n) end
  return self[i + 1]
end

function List:set(i, v)
  if type(i) ~= "number" or i < 0 or i >= self.n or i ~= floor(i) then outOfRange(i, self.n) end
  self[i + 1] = v
end

function List:Add(v)
  local n = self.n + 1
  self[n] = v
  self.n = n
end

function List:AddRange(src)
  for _, v in rt.each(src) do self:Add(v) end
end

function List:Insert(i, v)
  if i < 0 or i > self.n then outOfRange(i, self.n) end
  table.insert(self, i + 1, v)
  self.n = self.n + 1
end

function List:IndexOf(v)
  for i = 1, self.n do if rt.equals(self[i], v) then return i - 1 end end
  return -1
end

function List:LastIndexOf(v)
  for i = self.n, 1, -1 do if rt.equals(self[i], v) then return i - 1 end end
  return -1
end

function List:Contains(v) return self:IndexOf(v) >= 0 end

function List:RemoveAt(i)
  if i < 0 or i >= self.n then outOfRange(i, self.n) end
  table.remove(self, i + 1)
  self.n = self.n - 1
end

function List:Remove(v)
  local i = self:IndexOf(v)
  if i < 0 then return false end
  self:RemoveAt(i)
  return true
end

function List:RemoveRange(i, count)
  for _ = 1, count do self:RemoveAt(i) end
end

function List:RemoveAll(pred)
  local kept, removed = {}, 0
  for i = 1, self.n do
    if pred(self[i]) then removed = removed + 1 else kept[#kept + 1] = self[i] end
  end
  for i = 1, self.n do self[i] = kept[i] end
  self.n = self.n - removed
  return removed
end

function List:Clear()
  for i = 1, self.n do self[i] = nil end
  self.n = 0
end

function List:Reverse()
  local n = self.n
  for i = 1, floor(n / 2) do self[i], self[n - i + 1] = self[n - i + 1], self[i] end
end

local function lessThan(a, b)
  if type(a) == "string" and type(b) == "string" then return a < b end
  if type(a) == "number" and type(b) == "number" then return a < b end
  if type(a) == "table" then
    local mt = getmetatable(a)
    if mt and mt.CompareTo then return mt.CompareTo(a, b) < 0 end
  end
  return rt.str(a) < rt.str(b)
end

--- Stable merge sort, so equal keys keep their order like C#'s OrderBy.
local function stableSort(items, n, less)
  local tmp = {}
  local width = 1
  local src, dst = items, tmp
  while width < n do
    local i = 1
    while i <= n do
      local mid = math.min(i + width, n + 1)
      local hi = math.min(i + 2 * width, n + 1)
      local a, b, k = i, mid, i
      while a < mid and b < hi do
        if less(src[b], src[a]) then dst[k] = src[b]; b = b + 1 else dst[k] = src[a]; a = a + 1 end
        k = k + 1
      end
      while a < mid do dst[k] = src[a]; a = a + 1; k = k + 1 end
      while b < hi do dst[k] = src[b]; b = b + 1; k = k + 1 end
      i = i + 2 * width
    end
    src, dst = dst, src
    width = width * 2
  end
  if src ~= items then for i = 1, n do items[i] = src[i] end end
end

function List:Sort(cmp)
  local less = lessThan
  if cmp then less = function(a, b) return cmp(a, b) < 0 end end
  stableSort(self, self.n, less)
end

function List:ToList()
  local l = rt.list()
  for i = 1, self.n do l[i] = self[i] end
  l.n = self.n
  return l
end
List.ToArray = List.ToList

function List:GetRange(i, count)
  if i < 0 or count < 0 or i + count > self.n then outOfRange(i, self.n) end
  local l = rt.list()
  for k = 1, count do l[k] = self[i + k] end
  l.n = count
  return l
end

function List:ForEach(f) for i = 1, self.n do f(self[i]) end end

function List:Exists(pred) for i = 1, self.n do if pred(self[i]) then return true end end return false end

function List:Any(pred)
  if pred == nil then return self.n > 0 end
  return self:Exists(pred)
end

function List:All(pred) for i = 1, self.n do if not pred(self[i]) then return false end end return true end
List.TrueForAll = List.All

function List:Find(pred) for i = 1, self.n do if pred(self[i]) then return self[i] end end return nil end

function List:FindLast(pred) for i = self.n, 1, -1 do if pred(self[i]) then return self[i] end end return nil end

function List:FindIndex(pred) for i = 1, self.n do if pred(self[i]) then return i - 1 end end return -1 end

function List:FindAll(pred)
  local l = rt.list()
  for i = 1, self.n do if pred(self[i]) then l:Add(self[i]) end end
  return l
end
List.Where = List.FindAll

function List:CountWhere(pred)
  local c = 0
  for i = 1, self.n do if pred(self[i]) then c = c + 1 end end
  return c
end

function List:Select(f)
  local l = rt.list()
  for i = 1, self.n do l:Add(f(self[i])) end
  return l
end

local function noElements()
  rt.throw(newEx("InvalidOperationException", "Sequence contains no elements"))
end

function List:First(pred)
  for i = 1, self.n do if pred == nil or pred(self[i]) then return self[i] end end
  if pred then rt.throw(newEx("InvalidOperationException", "Sequence contains no matching element")) end
  noElements()
end

function List:FirstOrDefault(pred)
  for i = 1, self.n do if pred == nil or pred(self[i]) then return self[i] end end
  return nil
end

function List:Last(pred)
  for i = self.n, 1, -1 do if pred == nil or pred(self[i]) then return self[i] end end
  if pred then rt.throw(newEx("InvalidOperationException", "Sequence contains no matching element")) end
  noElements()
end

function List:LastOrDefault(pred)
  for i = self.n, 1, -1 do if pred == nil or pred(self[i]) then return self[i] end end
  return nil
end

function List:Single(pred)
  local found, value = 0, nil
  for i = 1, self.n do if pred == nil or pred(self[i]) then found = found + 1; value = self[i] end end
  if found ~= 1 then rt.throw(newEx("InvalidOperationException", "Sequence does not contain exactly one element")) end
  return value
end

function List:ElementAt(i) return self:get(i) end

function List:Sum(f)
  local s = 0
  for i = 1, self.n do s = s + (f and f(self[i]) or self[i]) end
  return s
end

function List:Average(f)
  if self.n == 0 then noElements() end
  return self:Sum(f) / self.n
end

function List:Min(f)
  if self.n == 0 then noElements() end
  local best = f and f(self[1]) or self[1]
  for i = 2, self.n do local v = f and f(self[i]) or self[i]; if v < best then best = v end end
  return best
end

function List:Max(f)
  if self.n == 0 then noElements() end
  local best = f and f(self[1]) or self[1]
  for i = 2, self.n do local v = f and f(self[i]) or self[i]; if v > best then best = v end end
  return best
end

function List:OrderBy(key)
  local l = self:ToList()
  stableSort(l, l.n, function(a, b) return lessThan(key(a), key(b)) end)
  return l
end

function List:OrderByDescending(key)
  local l = self:ToList()
  stableSort(l, l.n, function(a, b) return lessThan(key(b), key(a)) end)
  return l
end

function List:Take(count)
  local l = rt.list()
  for i = 1, math.min(count, self.n) do l:Add(self[i]) end
  return l
end

function List:Skip(count)
  local l = rt.list()
  for i = count + 1, self.n do l:Add(self[i]) end
  return l
end

function List:TakeWhile(pred)
  local l = rt.list()
  for i = 1, self.n do if not pred(self[i]) then break end l:Add(self[i]) end
  return l
end

function List:SkipWhile(pred)
  local l, skipping = rt.list(), true
  for i = 1, self.n do
    if skipping and not pred(self[i]) then skipping = false end
    if not skipping then l:Add(self[i]) end
  end
  return l
end

function List:Distinct()
  local l, seen = rt.list(), {}
  for i = 1, self.n do
    local v = self[i]
    local key = v == nil and seen or v
    if not seen[key] then seen[key] = true; l:Add(v) end
  end
  return l
end

function List:Concat(other)
  local l = self:ToList()
  l:AddRange(other)
  return l
end

function List:ToString() return "System.Collections.Generic.List`1" end

---------------------------------------------------------------------------------------------
-- Dictionary<K, V>: keeps insertion order like C# usually appears to
---------------------------------------------------------------------------------------------

local Dict = rt.class("Dictionary", nil)
rt.Dict = Dict

function rt.dict()
  return setmetatable({ n = 0, keys = {}, map = {}, has = {} }, Dict)
end

function rt.dictOf(...)
  local d = rt.dict()
  local args = table.pack(...)
  for i = 1, args.n, 2 do d:set(args[i], args[i + 1]) end
  return d
end

function rt.isDict(x) return getmetatable(x) == Dict end

local function nullKey()
  rt.throw(newEx("ArgumentNullException", "Value cannot be null. (Parameter 'key')"))
end

function Dict:get(k)
  if k == nil then nullKey() end
  if not self.has[k] then
    rt.throw(newEx("KeyNotFoundException", "The given key '" .. rt.str(k) .. "' was not present in the dictionary."))
  end
  return self.map[k]
end

function Dict:set(k, v)
  if k == nil then nullKey() end
  if not self.has[k] then
    self.has[k] = true
    self.n = self.n + 1
    self.keys[#self.keys + 1] = k
  end
  self.map[k] = v
end

function Dict:Add(k, v)
  if k == nil then nullKey() end
  if self.has[k] then
    rt.throw(newEx("ArgumentException", "An item with the same key has already been added. Key: " .. rt.str(k)))
  end
  self:set(k, v)
end

function Dict:ContainsKey(k) return k ~= nil and self.has[k] == true end

function Dict:ContainsValue(v)
  for _, k in ipairs(self.keys) do if rt.equals(self.map[k], v) then return true end end
  return false
end

function Dict:TryGet(k)
  if k ~= nil and self.has[k] then return true, self.map[k] end
  return false, nil
end

function Dict:GetValueOrDefault(k, default)
  if k ~= nil and self.has[k] then return self.map[k] end
  return default
end

function Dict:Remove(k)
  if k == nil or not self.has[k] then return false end
  self.has[k] = nil
  self.map[k] = nil
  self.n = self.n - 1
  for i, key in ipairs(self.keys) do
    if key == k then table.remove(self.keys, i) break end
  end
  return true
end

function Dict:Clear()
  self.n, self.keys, self.map, self.has = 0, {}, {}, {}
end

function Dict:KeysList()
  local l = rt.list()
  for _, k in ipairs(self.keys) do l:Add(k) end
  return l
end

function Dict:ValuesList()
  local l = rt.list()
  for _, k in ipairs(self.keys) do l:Add(self.map[k]) end
  return l
end

function Dict:ToString() return "System.Collections.Generic.Dictionary`2" end

---------------------------------------------------------------------------------------------
-- foreach
---------------------------------------------------------------------------------------------

local function listStep(l, i)
  i = i + 1
  if i <= l.n then return i, l[i] end
end

--- foreach over a List, Dictionary (KeyValuePair), string (chars) or a plain Lua table.
function rt.each(c)
  if c == nil then rt.throw(newEx("NullReferenceException", "foreach over null")) end
  local mt = getmetatable(c)
  if mt == List then return listStep, c, 0 end
  if mt == Dict then
    local keys = { table.unpack(c.keys) }
    local i = 0
    return function()
      i = i + 1
      local k = keys[i]
      if i <= #keys then return i, { Key = k, Value = c.map[k] } end
    end
  end
  if type(c) == "string" then
    return function(s, i)
      i = i + 1
      if i <= #s then return i, s:sub(i, i) end
    end, c, 0
  end
  if type(c) == "table" then
    if c[1] ~= nil or next(c) == nil then return ipairs(c) end
    local k
    return function()
      local v
      k, v = next(c, k)
      if k ~= nil then return k, { Key = k, Value = v } end
    end
  end
  rt.throw(newEx("InvalidOperationException", "cannot loop over a " .. type(c)))
end

--- .Count / .Length of something whose type is unknown (a value from Lua).
function rt.count(x)
  local mt = getmetatable(x)
  if mt == List or mt == Dict then return x.n end
  if type(x) == "string" or type(x) == "table" then return #x end
  rt.throw(newEx("InvalidOperationException", "a " .. type(x) .. " has no Count"))
end

---------------------------------------------------------------------------------------------
-- strings (chars are 1-character strings)
---------------------------------------------------------------------------------------------

local S = {}
rt.s = S

local function plainFind(s, sub, init) return string.find(s, sub, init, true) end

function S.charAt(s, i)
  if i < 0 or i >= #s then
    rt.throw(newEx("IndexOutOfRangeException", "Index was outside the bounds of the array."))
  end
  return s:sub(i + 1, i + 1)
end

function S.ToUpper(s) return s:upper() end
S.ToUpperInvariant = S.ToUpper
function S.ToLower(s) return s:lower() end
S.ToLowerInvariant = S.ToLower

local function trimSet(chars)
  if chars == nil then return "%s" end
  return "[" .. chars:gsub("[%]%%%-%^]", "%%%0") .. "]"
end

function S.Trim(s, chars)
  local set = trimSet(chars)
  return (s:gsub("^" .. set .. "+", ""):gsub(set .. "+$", ""))
end
function S.TrimStart(s, chars) return (s:gsub("^" .. trimSet(chars) .. "+", "")) end
function S.TrimEnd(s, chars) return (s:gsub(trimSet(chars) .. "+$", "")) end

function S.Substring(s, start, length)
  if start < 0 or start > #s or (length and (length < 0 or start + length > #s)) then
    rt.throw(newEx("ArgumentOutOfRangeException", "startIndex or length is outside the string."))
  end
  if length then return s:sub(start + 1, start + length) end
  return s:sub(start + 1)
end

function S.Contains(s, sub) return plainFind(s, sub, 1) ~= nil end
function S.StartsWith(s, sub) return s:sub(1, #sub) == sub end
function S.EndsWith(s, sub) return sub == "" or s:sub(-#sub) == sub end

function S.IndexOf(s, sub, start)
  local i = plainFind(s, sub, (start or 0) + 1)
  return i and i - 1 or -1
end

function S.LastIndexOf(s, sub)
  local last = -1
  local i = plainFind(s, sub, 1)
  while i do last = i - 1; i = plainFind(s, sub, i + 1) end
  return last
end

function S.Replace(s, old, new)
  if old == "" then rt.throw(newEx("ArgumentException", "String cannot be of zero length.")) end
  local out, i = {}, 1
  while true do
    local a, b = plainFind(s, old, i)
    if not a then break end
    out[#out + 1] = s:sub(i, a - 1)
    out[#out + 1] = new
    i = b + 1
  end
  out[#out + 1] = s:sub(i)
  return table.concat(out)
end

function S.PadLeft(s, width, ch) return string.rep(ch or " ", width - #s) .. s end
function S.PadRight(s, width, ch) return s .. string.rep(ch or " ", width - #s) end
function S.Insert(s, i, value) return s:sub(1, i) .. value .. s:sub(i + 1) end
function S.Remove(s, i, count)
  if count then return s:sub(1, i) .. s:sub(i + count + 1) end
  return s:sub(1, i)
end

--- Split by a string, a char, or a list of either; option 1 removes empty entries, 2 trims them.
function S.Split(s, sep, options)
  local seps = {}
  if sep == nil then seps = { " " }
  elseif getmetatable(sep) == List then for i = 1, sep.n do seps[#seps + 1] = sep[i] end
  else seps = { sep } end
  if type(options) ~= "number" then options = 0 end
  local parts, i = {}, 1
  while true do
    local bestA, bestB
    for _, p in ipairs(seps) do
      local a, b = plainFind(s, p, i)
      if a and (bestA == nil or a < bestA) then bestA, bestB = a, b end
    end
    if not bestA then break end
    parts[#parts + 1] = s:sub(i, bestA - 1)
    i = bestB + 1
  end
  parts[#parts + 1] = s:sub(i)
  local l = rt.list()
  for _, p in ipairs(parts) do
    if options >= 2 then p = S.Trim(p) end
    if not (options % 2 == 1 and p == "") then l:Add(p) end
  end
  return l
end

function S.ToCharArray(s)
  local l = rt.list()
  for i = 1, #s do l:Add(s:sub(i, i)) end
  return l
end

function S.Join(sep, items)
  local parts = {}
  for _, v in rt.each(items) do parts[#parts + 1] = rt.str(v) end
  return table.concat(parts, rt.str(sep))
end

function S.IsNullOrEmpty(s) return s == nil or s == "" end
function S.IsNullOrWhiteSpace(s) return s == nil or s:find("^%s*$") ~= nil end

local C = {}
rt.c = C
function C.IsDigit(c) return c:find("^%d$") ~= nil end
function C.IsLetter(c) return c:find("^%a$") ~= nil end
function C.IsWhiteSpace(c) return c:find("^%s$") ~= nil end
function C.IsUpper(c) return c:find("^%u$") ~= nil end
function C.IsLower(c) return c:find("^%l$") ~= nil end
function C.IsLetterOrDigit(c) return c:find("^%w$") ~= nil end
function C.IsPunctuation(c) return c:find("^%p$") ~= nil end

---------------------------------------------------------------------------------------------
-- Random and StringBuilder
---------------------------------------------------------------------------------------------

local Random = rt.class("Random", nil)
rt.Random = Random

function Random.new(seed)
  -- Lua has one global generator; seeding it keeps runs repeatable like new Random(seed)
  if seed then math.randomseed(seed) end
  return setmetatable({}, Random)
end

function Random:Next(a, b)
  if a == nil then return math.random(0, 2147483646) end
  if b == nil then a, b = 0, a end
  if b <= a then return a end
  return math.random(a, b - 1)
end

function Random:NextDouble() return math.random() end

local Builder = rt.class("StringBuilder", nil)
rt.Builder = Builder

function Builder.new(initial)
  local b = setmetatable({ parts = {} }, Builder)
  if initial then b.parts[1] = rt.str(initial) end
  return b
end

function Builder:Append(v) self.parts[#self.parts + 1] = rt.str(v) return self end
function Builder:AppendLine(v) self.parts[#self.parts + 1] = rt.str(v) .. "\n" return self end
function Builder:Clear() self.parts = {} return self end
function Builder:ToString() return table.concat(self.parts) end
function Builder:Length() return #table.concat(self.parts) end

return rt
