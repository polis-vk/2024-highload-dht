function request()
    id = math.random(1, 300000)
    path = "/v0/entity?id=" .. id
    method = "GET"
    return wrk.format(method, path, body)
end