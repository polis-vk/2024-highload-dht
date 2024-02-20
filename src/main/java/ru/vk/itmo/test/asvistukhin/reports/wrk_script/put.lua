counter = 0

function request()
    path = "/v0/entity?id=" .. counter
    wrk.method = "PUT"
    wrk.body = randomString()
    counter = counter + 1
    return wrk.format(nil, path)
end

function randomString()
	local length = math.random(10,100)
	local array = {}
	for i = 1, length do
		array[i] = string.char(math.random(55, 123))
	end
	return table.concat(array)
end
