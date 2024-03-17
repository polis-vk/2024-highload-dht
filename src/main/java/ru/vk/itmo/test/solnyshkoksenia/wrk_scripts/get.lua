math.randomseed(os.time())

not_found_counter = 0
reject_counter = 0

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

request_get = function()
    local headers = {}
    headers["Host"] = "localhost:8080"

    local length = 3
    local id = randomString(length)

    local path = "/v0/entity?id=" .. id
    return wrk.format("GET", path, headers)
end

function request()
    return request_get()
end

function response(status, headers, body)
    if status == 402 then
        print("here")
    end
end