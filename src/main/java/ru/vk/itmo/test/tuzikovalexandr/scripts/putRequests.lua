id = 0
function request()
    id = id + 1
    key = "k" .. math.random(10000000)
    path = "/v0/entity?id=" .. key

    value = "v" .. math.random(10000000)

    --headers = { }
    --headers["Host"] = "localhost:8080"

    return wrk.format("PUT", path, headers, value)
end