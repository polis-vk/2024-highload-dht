wrk.method = "PUT"

math.randomseed(os.time())

request = function()
    id = math.random(100000)
    wrk.body = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    path = "/v0/entity?id=" .. id
    return wrk.format(nil, path)
end