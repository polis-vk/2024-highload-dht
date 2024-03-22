wrk.method = "GET"

math.randomseed(os.time())

request = function()
    id = math.random(100000)
    path = "/v0/entity?id=" .. id
    return wrk.format(nil, path)
end