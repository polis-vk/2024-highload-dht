local random = require("math").random
local char = require("string").char

local function randomString(n)
    local result = {}
    for i = 1, n do
        result[i] = char(random(33, 126))  -- ASCII символы с 33 по 126
    end
    return table.concat(result)
end

counter = 0
function request()
    counter = counter + 1
    local headers = {}
    headers["Host"] = "localhost:8080"
    local key = "key" .. counter
    local value = "value$" .. randomString(100)
    return wrk.format("PUT", "/v0/entity?id=" .. key, headers, value)
end