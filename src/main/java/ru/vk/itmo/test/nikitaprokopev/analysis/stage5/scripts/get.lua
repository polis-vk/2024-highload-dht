function request()
    local headers = {}
    headers["Host"] = "localhost:8080"
    local id = math.random(1, 10000)
    local key = "key" .. id
    return wrk.format("GET", "/v0/entity?id=" .. key .. "&ack=1&from=3", headers)
end