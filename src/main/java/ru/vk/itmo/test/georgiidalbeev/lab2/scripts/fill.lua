math.randomseed(os.time())

function random_string(length)
    local res = ""
    for i = 1, length do
        res = res .. string.char(math.random(97, 122))
    end
    return res
end

counter = 0
request = function()
    counter = counter + 1
    local headers = {}
    headers["Host"] = "localhost"
    local id = counter
    local body = random_string(10)
    return wrk.format("PUT", "/v0/entity?id=" .. id, headers, body)
end