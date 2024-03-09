request_put = function()
    headers = {}
    headers["Host"] = "localhost:8080"
    id = math.random(1,100000)
    path = "/v0/entity?id=" .. id
    return wrk.format("GET", path, headers)
end

function request()
    return request_put()
end