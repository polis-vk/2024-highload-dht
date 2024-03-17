function request()
    path = "/v0/entity?id=" .. math.random(0, 595000)
    return wrk.format("GET", path)
end
