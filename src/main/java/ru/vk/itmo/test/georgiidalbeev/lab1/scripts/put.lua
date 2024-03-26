math.randomseed(os.time())

function random_string(length)
    local res = ""
    for i = 1, length do
        res = res .. string.char(math.random(97, 122))
    end
    return res
end

request = function()
    local headers = {}
    headers["Host"] = "localhost"
    local id = math.random(1, 1000)
    local body = random_string(10)
    return wrk.format("PUT", "/v0/entity?id=" .. id, headers, body)
end