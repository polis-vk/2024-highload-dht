---
--- Created by vitekkor.
--- DateTime: 17.02.2024 19:06
---

host = "localhost"
port = 8080

counter = 0

local charset = {}  do -- [0-9a-zA-Z]
    for c = 48, 57  do table.insert(charset, string.char(c)) end
    for c = 65, 90  do table.insert(charset, string.char(c)) end
    for c = 97, 122 do table.insert(charset, string.char(c)) end
end

local function randomString(length)
    if not length or length <= 0 then
        return ''
    end
    return randomString(length - 1) .. charset[math.random(1, #charset)]
end

function generateRandomBytes(length)
    local result = {}
    for i = 1, length do
        result[i] = math.random(0, 255)
    end

    return table.concat(result)
end

request = function()
    math.randomseed(counter)
    path = "/v0/entity?id=" .. randomString(math.random(1, 10))
    method = "PUT"
    math.randomseed(counter)
    body = generateRandomBytes(1024)
    counter = counter + 100
    return wrk.format(method, path, nil, body)
end
