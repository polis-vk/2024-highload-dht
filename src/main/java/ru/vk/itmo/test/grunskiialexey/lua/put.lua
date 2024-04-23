function request()
    key = math.random(0, 1000000)
    path = "/v0/entity?id=" .. key
    return wrk.format("PUT", path, {}, tostring(key))
end