math.randomseed(os.time())

function generate_value(length)
    local alphabet = "AaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpRrSsTtUuVvWwXxYyZz"
    local result = ""

    for _ = 1, length do
        local alphabet_index = math.random(1, #alphabet)
        result = result .. tostring(alphabet[alphabet_index])
    end

    return result
end

wrk.host = "localhost"
wrk.port = "8080"

function request()
    local value_length = math.random(5, 20)
    wrk.body = generate_value(value_length)
    local id = tostring(math.random(0, 1000000))
    local path = "/v0/entity?id=" .. id
    return wrk.format("PUT", path)
end