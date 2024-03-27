wrk.method = "GET"

request = function()
    path = "/v0/entity?id=" .. math.random(0, 170000)
    return wrk.format(nil, path)
end