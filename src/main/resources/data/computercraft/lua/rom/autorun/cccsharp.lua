-- Tab-completion of file names for the csharp program (CC: CSharp).
local completion = require("cc.shell.completion")
shell.setCompletionFunction("rom/programs/csharp.lua", completion.build(completion.file))
