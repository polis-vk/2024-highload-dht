id = 1000000
function request()
    headers = { }
    headers["Host"] = "localhost:8080"
    id = id + 1
    key = "key=" .. id
    local valueAsBody = "value=" .. (tostring({}):sub(math.random(25, 50)))
    return wrk.format("PUT", "/v0/entity?id=" .. key, headers, valueAsBody)
end