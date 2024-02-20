wrk.method = "GET"

request = function()
    path = "/v0/entity?id=1"
    return wrk.format(nil, path)
end