id = 0
function request()
    body = string.rep("meow", 100)
    id = id + 1
    path = "/v0/entity?id=" .. id
    return wrk.format("PUT", path, nil, body)
end