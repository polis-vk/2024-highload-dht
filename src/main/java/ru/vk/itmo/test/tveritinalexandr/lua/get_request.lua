function request()
    local headers = {}
    headers["Host"] = "localhost"
    local id = math.random(1, 2000)
    local path = "/v0/entity?id=" .. id
    return wrk.format("GET", path)
end