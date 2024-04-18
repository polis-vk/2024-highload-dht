function request()
    local headers = {}
    headers["Host"] = "localhost:8080"
    local id = math.random(1, 100000)
    local key = "key" .. id
    return wrk.format("GET", "/v0/entity?id=" .. key, headers)
end