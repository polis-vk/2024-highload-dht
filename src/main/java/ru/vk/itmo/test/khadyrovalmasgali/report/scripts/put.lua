local charset = {}  do -- [0-9a-zA-Z]
    for c = 48, 57  do table.insert(charset, string.char(c)) end
    for c = 65, 90  do table.insert(charset, string.char(c)) end
    for c = 97, 122 do table.insert(charset, string.char(c)) end
end

local function randomString(length)
    if not length or length <= 0 then return '' end
    return randomString(length - 1) .. charset[math.random(1, #charset)]
end

counter = 0

request = function()
    headers = {}
    headers["Host"] = "localhost:8080"
    counter = counter + 1
    return wrk.format("PUT", "/v0/entity?id=" .. counter, headers, randomString(math.random(5, 50)))
end