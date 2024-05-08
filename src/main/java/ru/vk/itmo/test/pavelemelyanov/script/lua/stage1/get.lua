function request()
    id = math.random(1, 1000)
    path = "/v0/entity?id=" .. id
    return wrk.format("GET", path, body)
end
