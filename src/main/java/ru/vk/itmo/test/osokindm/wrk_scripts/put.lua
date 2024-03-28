minId = 1
maxId = 100000000

function getRandomId()
    return math.random(minId, maxId)
end

function getRandomString(length)
    charset = {}
    -- ASCII uppercase letters
    for i = 65, 90 do
        table.insert(charset, string.char(i))
    end
    -- ASCII lowercase letters
    for i = 97, 122 do
        table.insert(charset, string.char(i))
    end

    math.randomseed(os.time())
    str = ''
    for _ = 1, length do
        local randIndex = math.random(1, #charset)
        str = str .. charset[randIndex]
    end
    return str
end

function request()
    id = getRandomId()
    path = "/v0/entity?id=" .. id
    body = getRandomString(10)
    return wrk.format("PUT", path, nil, body)
end