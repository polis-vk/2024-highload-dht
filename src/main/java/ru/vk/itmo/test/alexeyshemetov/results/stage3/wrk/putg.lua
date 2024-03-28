wrk.method = "PUT"

math.randomseed(os.time())

request = function()
    id = math.random(1000)
    wrk.body = string.rep("HeDontKnow", 4444) .. id
    path = "/v0/entity?id=" .. id
    return wrk.format(nil, path)
end