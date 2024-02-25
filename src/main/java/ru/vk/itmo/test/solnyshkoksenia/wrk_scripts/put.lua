math.randomseed(os.time())

function randomChar()
    local chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    local rint = math.random(1, #chars)
    return chars:sub(rint, rint)
end

function randomString(length)
    local res = ""
    for i = 1, length do
        res = res .. randomChar()
    end
    return res
end

request_put = function()
    local headers = {}
    headers["Host"] = "localhost:8080"

    local length = math.random(10, 100)
    local id = randomString(length)
    local body = randomString(300)

    local path = "/v0/entity?id=" .. id
    return wrk.format("PUT", path, headers, body)
end

function request()
    return request_put()
end