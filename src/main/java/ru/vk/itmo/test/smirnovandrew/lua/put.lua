id = 0

function random_string()
    str = ""
    for i = 1, math.random(2, 100)
    do
        str = str .. string.char(math.random(97, 122))
    end
    return str
end

function request()
    id = id + 1
    path = "/v0/entity?id=" .. id
    headers = {}
    headers["Host"] = "localhost:8080"
    body = random_string()
    return wrk.format("PUT", path, headers, body)
end