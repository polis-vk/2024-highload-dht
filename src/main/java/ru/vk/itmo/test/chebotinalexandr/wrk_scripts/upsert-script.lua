id = 0
function request()
    headers = { }
    headers["Host"] = "localhost:8080"
    id = id + 1
    key = "k" .. id
    local valueAsBody = "v" .. math.random(1, 500000)
    return wrk.format("PUT", "/v0/entity?id=" .. key, headers, valueAsBody)
end