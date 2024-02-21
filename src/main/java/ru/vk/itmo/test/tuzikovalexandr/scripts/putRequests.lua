id = 0
function request()
    id = id + 1
    key = "k" .. id
    path = "/v0/entity?id=" .. key

    value = "v" .. math.random(100000)

    --headers = { }
    --headers["Host"] = "localhost:8080"

    return wrk.format("PUT", path, headers, value)
end