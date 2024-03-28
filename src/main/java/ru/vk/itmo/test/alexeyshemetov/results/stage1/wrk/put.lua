wrk.method = "PUT"

math.randomseed(os.time())

request = function()
    id = math.random(9876543210000)
    wrk.body = ":HeDontKnow:" .. id
    path = "/v0/entity?id=" .. id
    return wrk.format(nil, path)
end