function request()
    id = math.random(1, 100000)
    key = "k" .. id
    path = "/v0/entity?id=" .. key

    --headers = { }
    --headers["Host"] = "localhost:8080"

    return wrk.format("GET", path, body)
end