id = 0
function request()
    headers = {}
    headers["Host"] = "localhost:8080"
    id = id + 1
    body = "I'm just a body of a lua script, but what for? " .. math.random(1, 1000000)
    return wrk.format("PUT", "/v0/entity?id=" .. id, headers, body)
end