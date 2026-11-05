package com.skilllab.admin.ai;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "ai_device_project")
public class AiDeviceProjectEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 160)
    private String manufacturer;

    @Column(length = 160)
    private String model;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected AiDeviceProjectEntity() {
    }

    public AiDeviceProjectEntity(String id, String name, String manufacturer, String model) {
        this.id = id;
        this.name = name;
        this.manufacturer = manufacturer;
        this.model = model;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getManufacturer() { return manufacturer; }
    public String getModel() { return model; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
