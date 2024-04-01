function request()
    path = "/v0/entity?id=" .. math.random(0, 52000)
    wrk.method = "GET"
    return wrk.format(nil, path)
end
