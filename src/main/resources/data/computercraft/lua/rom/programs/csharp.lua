-- csharp: compile and run a C# program (CC: CSharp).
--   csharp prog.cs [args...]   compile prog.cs to prog.lua and run it
--   csharp -c prog.cs          only compile (writes prog.lua)

local args = { ... }
local compileOnly = false
if args[1] == "-c" or args[1] == "--compile" then
  compileOnly = true
  table.remove(args, 1)
end

if #args < 1 then
  print("Usage: csharp <file.cs> [args...]")
  print("       csharp -c <file.cs>  (only write the .lua)")
  return
end

if type(csharp) ~= "table" or type(csharp.compile) ~= "function" then
  printError("The CC: CSharp API is missing. Is the mod installed on the server?")
  return
end

local path = shell.resolve(args[1])
if not fs.exists(path) and fs.exists(path .. ".cs") then path = path .. ".cs" end
if not fs.exists(path) or fs.isDir(path) then
  printError("No such file: " .. args[1])
  return
end

local handle = fs.open(path, "r")
local source = handle.readAll()
handle.close()

local name = fs.getName(path)
local lua, map = csharp.compile(source, name)
if not lua then
  printError(map)
  return
end

local out = path:gsub("%.cs$", "")
if out == path then out = path end
out = out .. ".lua"
local w = fs.open(out, "w")
w.write(lua)
w.close()

if compileOnly then
  print("Wrote " .. out)
  return
end

local chunkName = fs.getName(out)
local fn, err = load(lua, "@" .. chunkName, "t", _ENV)
if not fn then
  printError(err)
  return
end

-- Rewrite "prog.lua:42" positions into the matching C# line.
local escaped = chunkName:gsub("%p", "%%%0")
local function toCSharp(message)
  return (tostring(message):gsub(escaped .. ":(%d+)", function(l)
    local cs = map[tonumber(l)]
    return name .. ":" .. (cs and tostring(cs) or ("?lua" .. l))
  end))
end

local ok, result = xpcall(function() return fn(table.unpack(args, 2)) end, function(e)
  if e == "Terminated" then return e end
  if type(e) == "table" and e.Message ~= nil then
    local mt = getmetatable(e)
    local where = e.__where and (toCSharp(e.__where) .. ": ") or ""
    return where .. "Unhandled exception. " .. tostring(mt and mt.__name or "Exception") .. ": " .. tostring(e.Message)
  end
  return toCSharp(e)
end)
if not ok then printError(result) end
