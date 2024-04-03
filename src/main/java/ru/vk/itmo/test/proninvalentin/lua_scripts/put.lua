id = 1234567

function request()
    method = "PUT"
    path = "/v0/entity?id=" .. tostring(id)
    body = tostring(id)
    --body = string.rep(tostring(counter), 1)
    id = id + 1
    return wrk.format(method, path, headers, body)
end