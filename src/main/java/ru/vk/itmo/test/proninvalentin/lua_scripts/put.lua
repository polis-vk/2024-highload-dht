id = 0

function request()
    body = string.rep("random_value", 100)
    method = "PUT"
    id = id + 1
    path = "/v0/entity?id=" .. id
    return wrk.format(method, path, headers, body)
end