import type { Component } from 'vue'
import { Bell, Box, Cpu, DataBoard, Setting, SetUp, Menu } from '@element-plus/icons-vue'

/** 菜单图标（按名称显式引入，保证 tree-shaking） */
export const menuIcons: Record<string, Component> = {
  Bell,
  Box,
  Cpu,
  DataBoard,
  Setting,
  SetUp,
  Menu,
}
