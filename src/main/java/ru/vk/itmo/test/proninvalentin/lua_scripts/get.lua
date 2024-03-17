id = 1234567

function request()
    method = "GET"
    path = "/v0/entity?id=" .. tostring(id)
    id = id + 1
    return wrk.format(method, path, body)
end