id = 1234567

function request()
    method = "PUT"
    path = "/v0/entity?id=" .. tostring(id)
    body = tostring(id)
    id = id + 1
    return wrk.format(method, path, headers, body)
end