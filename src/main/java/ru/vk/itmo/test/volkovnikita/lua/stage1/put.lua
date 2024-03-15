id = 0
function request()
    headers = {}
    headers["Host"] = "localhost:8080"
    id = id + 1
    body = math.random(1, 555555)
    return wrk.format("PUT", "/v0/entity?id=" .. id, headers, body)
end