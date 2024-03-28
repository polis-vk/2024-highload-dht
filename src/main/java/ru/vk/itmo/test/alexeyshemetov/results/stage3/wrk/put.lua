wrk.method = "PUT"

math.randomseed(os.time())

request = function()
    id = math.random(986342896523245)
    wrk.body = ":HeDontKnow:" .. id
    path = "/v0/entity?id=" .. id
    return wrk.format(nil, path)
end