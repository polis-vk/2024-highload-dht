request_put = function()
    headers = {}
    headers["Host"] = "localhost:8080"
    id = math.random(1,100000000)
    body = math.random(1,100000000)
    path = "/v0/entity?id=" .. id
    return wrk.format("PUT", path, headers, body)
end

function request()
    return request_put()
end